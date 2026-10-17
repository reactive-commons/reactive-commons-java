package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageNotifier;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivecommons.async.rabbit.health.RabbitReactiveHealthIndicator;
import org.reactivecommons.async.rabbit.lifecycle.EventLoopGroupLifecycleManager;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static reactor.rabbitmq.ExchangeSpecification.exchange;

/**
 * Runs {@link RabbitMQSetupUtils} against a REAL RabbitMQ broker to check that its cached resources survive:
 * <ul>
 *     <li>a channel-level error on the sender's resource management channel ({@code declare}/{@code bind});</li>
 *     <li>several Spring contexts in the same JVM (tests with {@code @DirtiesContext}, cached test contexts,
 *     context restarts), which share the static Netty event loop group;</li>
 *     <li>a connection closed by the broker, as reported by the health indicator.</li>
 * </ul>
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class RabbitMQSetupUtilsRabbitContainerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

    /**
     * A channel-level error closes the channel, and the client never recovers channels closed by channel-level
     * errors. The sender's resource management channel must therefore be replaced, not cached forever.
     */
    @Test
    void senderTopologyKeepsWorkingAfterAChannelErrorClosesItsChannel() throws Exception {
        AsyncProps props = asyncProps(RABBIT.getHost(), RABBIT.getAmqpPort(), RABBIT.getAdminUsername(),
                RABBIT.getAdminPassword());
        ConnectionFactoryProvider provider = RabbitMQSetupUtils.connectionFactoryProvider(props, (cf, p) -> cf);
        ReactiveMessageSender sender = RabbitMQSetupUtils.createMessageSender(provider, props,
                new RabbitJacksonMessageConverter(new JsonMapper()), new UnroutableMessageNotifier());
        TopologyCreator topology = sender.getTopologyCreator();
        String suffix = UUID.randomUUID().toString();

        // 1. Healthy: the exchange is declared.
        topology.declare(exchange("tc.topic." + suffix).type("topic").durable(true)).block(TIMEOUT);

        // 2. Channel-level error: same exchange with another type => 406 PRECONDITION_FAILED closes the channel.
        assertThatThrownBy(() -> topology.declare(exchange("tc.topic." + suffix).type("direct").durable(true))
                .block(TIMEOUT))
                .hasStackTraceContaining("PRECONDITION_FAILED");

        // 3. A valid declaration works again, on a new channel.
        topology.declare(exchange("tc.other." + suffix).type("direct").durable(true)).block(TIMEOUT);
        assertThat(exchangeExists("tc.other." + suffix)).as("the valid exchange was declared").isTrue();
    }

    /**
     * The health indicator reports the connection the application uses (it does not open one per check), and follows
     * its state: DOWN while the client recovers it after the broker closed it, UP again once recovered.
     */
    @Test
    void healthReflectsTheApplicationConnectionWithoutOpeningNewOnes() throws Exception {
        AsyncProps props = asyncProps(RABBIT.getHost(), RABBIT.getAmqpPort(), RABBIT.getAdminUsername(),
                RABBIT.getAdminPassword());
        ConnectionFactoryProvider provider = RabbitMQSetupUtils.connectionFactoryProvider(props, (cf, p) -> cf);
        RabbitMQSetupUtils.createMessageSender(provider, props, new RabbitJacksonMessageConverter(new JsonMapper()),
                new UnroutableMessageNotifier()); // establishes the domain connection
        RabbitReactiveHealthIndicator health = new RabbitReactiveHealthIndicator("app",
                () -> RabbitMQSetupUtils.establishedConnection(provider.getConnectionFactory()));

        // 1. UP once the application connection is established.
        assertThat(awaitStatus(health, RCHealth.Status.UP, Duration.ofSeconds(15)).details())
                .containsEntry("domain", "app")
                .containsKey("version");

        // 2. Checks do not open connections (the broker sees the same number of connections).
        int connectionsBefore = brokerConnections();
        for (int i = 0; i < 20; i++) {
            assertThat(health.health().block(TIMEOUT).status()).isEqualTo(RCHealth.Status.UP);
        }
        assertThat(brokerConnections()).isEqualTo(connectionsBefore);

        // 3. The broker closes the connection (e.g. node restart, admin action): DOWN while the client recovers it.
        RABBIT.execInContainer("rabbitmqctl", "close_all_connections", "health test");
        RCHealth down = awaitStatus(health, RCHealth.Status.DOWN, Duration.ofSeconds(10));
        System.out.println("[REAL BROKER] health while recovering: " + down.details());
        assertThat(down.details().get("error").toString()).startsWith("Connection to RabbitMQ is closed");

        // 4. UP again after the automatic recovery (default network recovery interval: 5 s).
        awaitStatus(health, RCHealth.Status.UP, Duration.ofSeconds(30));
    }

    /**
     * A context created after another one was closed can connect: the shared event loop group shut down with the
     * first context is re-created. It runs in a separate JVM because it changes the static state of the starter.
     */
    @Test
    void contextCreatedAfterAnotherOneWasClosedConnects() throws Exception {
        List<String> results = runProbe("sequential");

        assertThat(results).containsExactly("PROBE FIRST=OK", "PROBE SECOND=OK", "PROBE SECOND_CONNECTION=OK");
    }

    /**
     * Closing one context does not break another context that is still alive in the same JVM (e.g. Spring keeps
     * several test contexts cached): the shared event loop group is only shut down with the last context.
     */
    @Test
    void closingOneContextDoesNotBreakAnotherLiveContext() throws Exception {
        List<String> results = runProbe("concurrent");

        assertThat(results).containsExactly("PROBE FIRST=OK", "PROBE SECOND=OK",
                "PROBE SECOND_AFTER_FIRST_CLOSED=OK");
    }

    private static List<String> runProbe(String scenario) throws Exception {
        List<String> output = runInSeparateJvm(ContextRestartProbe.class, scenario, RABBIT.getHost(),
                String.valueOf(RABBIT.getAmqpPort()), RABBIT.getAdminUsername(), RABBIT.getAdminPassword());
        List<String> results = output.stream().filter(line -> line.startsWith("PROBE ")).toList();
        System.out.println("[REAL BROKER] " + scenario + " contexts probe: " + results);
        return results;
    }

    /**
     * Simulates Spring contexts in the same JVM, using the same entry points as the starter:
     * {@link EventLoopGroupLifecycleManager} (started/stopped with each context) and
     * {@link RabbitMQSetupUtils#createTopologyCreator}.
     */
    static final class ContextRestartProbe {
        public static void main(String[] args) {
            String scenario = args[0];
            String host = args[1];
            int port = Integer.parseInt(args[2]);
            String username = args[3];
            String password = args[4];
            try {
                EventLoopGroupLifecycleManager firstContext = new EventLoopGroupLifecycleManager();
                TopologyCreator firstTopology = topology(asyncProps(host, port, username, password));
                firstContext.start();
                System.out.println("PROBE FIRST=" + declare(firstTopology));

                if ("sequential".equals(scenario)) {
                    firstContext.stop(); // first context closed before the second one is created
                    EventLoopGroupLifecycleManager secondContext = new EventLoopGroupLifecycleManager();
                    TopologyCreator secondTopology = topology(asyncProps(host, port, username, password));
                    secondContext.start();
                    System.out.println("PROBE SECOND=" + declare(secondTopology));
                    System.out.println("PROBE SECOND_CONNECTION="
                            + connectOnce(asyncProps(host, port, username, password)));
                } else {
                    EventLoopGroupLifecycleManager secondContext = new EventLoopGroupLifecycleManager();
                    TopologyCreator secondTopology = topology(asyncProps(host, port, username, password));
                    secondContext.start();
                    System.out.println("PROBE SECOND=" + declare(secondTopology));
                    firstContext.stop(); // first context closed while the second one is alive
                    System.out.println("PROBE SECOND_AFTER_FIRST_CLOSED=" + declare(secondTopology));
                }
            } finally {
                System.out.flush();
                System.exit(0); // connection threads would keep the JVM alive
            }
        }

        private static TopologyCreator topology(AsyncProps props) {
            return RabbitMQSetupUtils.createTopologyCreator(props, (cf, p) -> cf);
        }

        /**
         * A single connection attempt with the factory the starter builds, to show why a connection fails.
         */
        private static String connectOnce(AsyncProps props) {
            ConnectionFactory factory = RabbitMQSetupUtils.connectionFactoryProvider(props, (cf, p) -> cf)
                    .getConnectionFactory();
            try (Connection ignored = factory.newConnection("tc-probe")) {
                return "OK";
            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                return "FAILED: " + e + " | root cause: " + root;
            }
        }

        private static String declare(TopologyCreator topology) {
            try {
                topology.declare(exchange("tc.probe." + UUID.randomUUID()).type("direct").durable(false)
                                .autoDelete(true))
                        .block(TIMEOUT);
                return "OK";
            } catch (Exception e) {
                return "FAILED: " + e;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private static AsyncProps asyncProps(String host, int port, String username, String password) {
        RabbitProperties connection = new RabbitProperties();
        connection.setHost(host);
        connection.setPort(port);
        connection.setUsername(username);
        connection.setPassword(password);
        AsyncProps props = new AsyncProps();
        props.setAppName("tc-app");
        props.setConnectionProperties(connection);
        return props;
    }

    private static RCHealth awaitStatus(RabbitReactiveHealthIndicator health, RCHealth.Status expected,
                                        Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        RCHealth last = null;
        while (System.nanoTime() < deadline) {
            last = health.health().block(TIMEOUT);
            if (last != null && last.status() == expected) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Health did not become " + expected + " within " + timeout + ", last: " + last);
    }

    private static int brokerConnections() throws Exception {
        return (int) RABBIT.execInContainer("rabbitmqctl", "-q", "list_connections", "name").getStdout()
                .lines().filter(line -> !line.isBlank()).count();
    }

    private static boolean exchangeExists(String name) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        try (Connection connection = factory.newConnection("tc-admin")) {
            try {
                connection.createChannel().exchangeDeclarePassive(name);
                return true;
            } catch (IOException notFound) {
                return false;
            }
        }
    }

    private static List<String> runInSeparateJvm(Class<?> mainClass, String... args) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-cp",
                System.getProperty("java.class.path"), mainClass.getName()));
        command.addAll(List.of(args));
        // output to a file: the probe logs connection retries, a full pipe would block it
        Path output = Files.createTempFile("rabbit-probe", ".log");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Probe JVM did not finish in time");
            }
            return Files.readAllLines(output, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
