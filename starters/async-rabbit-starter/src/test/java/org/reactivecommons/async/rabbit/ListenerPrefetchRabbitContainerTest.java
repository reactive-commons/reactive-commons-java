package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenCommands;

/**
 * A command listener whose handlers never complete consumes a queue holding 2 000 commands on a REAL RabbitMQ broker:
 * the deliveries the broker sends without waiting for their acknowledgements are bounded by {@code prefetchCount},
 * which must be at least 1.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ListenerPrefetchRabbitContainerTest {

    private static final int COMMANDS = 2_000;

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @Configuration
    static class NeverEndingHandler {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register().handleCommand("held.command", command -> Mono.never(), Map.class);
        }
    }

    @Test
    void theDefaultPrefetchBoundsTheUnacknowledgedDeliveries() throws Exception {
        String outcome = unacknowledgedWith("pfd", null);
        System.out.printf("[REAL BROKER] default prefetchCount: %s%n", outcome);
        assertThat(outcome).isEqualTo("unacknowledged=250, consumers=1");
    }

    /**
     * A prefetch of 0 means no limit for the broker: it would send the whole queue to the listener (2 000 of 2 000
     * deliveries unacknowledged before this check).
     */
    @Test
    void aZeroPrefetchCountIsRejectedAtStartup() throws Exception {
        String outcome = unacknowledgedWith("pfz", "0");
        System.out.printf("[REAL BROKER] prefetchCount 0: %s%n", outcome);
        assertThat(outcome).startsWith("the application did not start: java.lang.IllegalArgumentException: "
                + "prefetchCount must be at least 1");
    }

    @Test
    void aNegativePrefetchCountIsRejectedAtStartup() throws Exception {
        String outcome = unacknowledgedWith("pfn", "-1");
        System.out.printf("[REAL BROKER] prefetchCount -1: %s%n", outcome);
        assertThat(outcome).startsWith("the application did not start: java.lang.IllegalArgumentException: "
                + "prefetchCount must be at least 1");
    }

    private String unacknowledgedWith(String prefix, String prefetchCount) throws Exception {
        String queue = prefix + "-app";
        Map<String, Object> properties = prefetchCount == null
                ? Map.of() : Map.of("app.async.app.prefetchCount", prefetchCount);
        try (Connection connection = connect(); Channel channel = connection.createChannel()) {
            // the commands are waiting before the listener starts
            channel.queueDeclare(queue, true, false, false, null);
            for (int i = 0; i < COMMANDS; i++) {
                channel.basicPublish("", queue, new AMQP.BasicProperties.Builder().contentType("application/json")
                                .build(),
                        ("{\"name\":\"held.command\",\"commandId\":\"" + UUID.randomUUID() + "\",\"data\":{}}")
                                .getBytes(StandardCharsets.UTF_8));
            }
            String outcome;
            try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, prefix,
                    false, false, properties, NeverEndingHandler.class, ListenCommands.class)) {
                RabbitMQBrokerProvider provider = (RabbitMQBrokerProvider) context.getBean(ConnectionManager.class)
                        .getProviders().get(RabbitScenarioSupport.DEFAULT_DOMAIN);
                assertThat(provider.props().getBrokerConfigProps().getCommandsQueue()).isEqualTo(queue);
                // the broker delivers the waiting commands up to the prefetch: their count stops growing
                AtomicReference<String> delivered = new AtomicReference<>("");
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).until(() -> {
                    String unacknowledged = column(queue, "messages_unacknowledged");
                    return !"0".equals(unacknowledged) && unacknowledged.equals(delivered.getAndSet(unacknowledged));
                });
                outcome = "unacknowledged=" + column(queue, "messages_unacknowledged")
                        + ", consumers=" + column(queue, "consumers");
            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                outcome = "the application did not start: " + root;
            }
            channel.queueDelete(queue);
            return outcome;
        }
    }

    private static String column(String queue, String column) throws Exception {
        String listing = RABBIT.execInContainer("rabbitmqctl", "-q", "list_queues", "name", column).getStdout();
        return listing.lines()
                .map(line -> line.trim().split("\\s+"))
                .filter(columns -> columns.length == 2 && columns[0].equals(queue))
                .map(columns -> columns[1])
                .findFirst()
                .orElse("?");
    }

    private static Connection connect() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        return factory.newConnection();
    }
}
