package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.ReactiveCommonsConfig;
import org.reactivecommons.async.starter.config.ReactiveCommonsListenersConfig;
import org.reactivecommons.async.starter.impl.common.rabbit.RabbitMQConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real wiring of the starter (every domain of the application) against a REAL RabbitMQ broker and checks
 * how many resources the discard notifier costs.
 *
 * <p>Every {@code ReactiveMessageSender} opens 5 publishing flows (4 with publisher confirms and 1 without), each
 * one with its own channel, and 26 sender threads. The discard notifier used to create a new sender, over the
 * connection of the default domain, for EVERY domain. The channels that the broker reports for each connection are
 * the source of truth.
 *
 * <p>The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = {
        RabbitMQConfig.class,
        AsyncPropsDomain.class,
        RabbitMQBrokerProviderFactory.class,
        ReactiveCommonsConfig.class,
        ReactiveCommonsListenersConfig.class
})
class DiscardNotifierSenderRabbitContainerTest {

    private static final int CHANNELS_PER_SENDER = 5;
    private static final String DOMAIN_EVENTS_EXCHANGE = "domainEvents";

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        // The default domain plus two more. Each one has its own name, so the broker tells their connections apart.
        registry.add("app.async.app.appName", () -> "tc-app");
        registry.add("app.async.app.listenReplies", () -> "false");
        registry.add("app.async.second.appName", () -> "tc-second");
        registry.add("app.async.second.listenReplies", () -> "false");
        registry.add("app.async.third.appName", () -> "tc-third");
        registry.add("app.async.third.listenReplies", () -> "false");
    }

    @Autowired
    private ConnectionManager manager;

    @Test
    void discardNotifiersDoNotOpenExtraSendersOnTheDefaultDomainConnection() throws Exception {
        assertThat(manager.getProviders()).containsOnlyKeys("app", "second", "third");

        int appChannels = settledChannels("tc-app-");
        int secondChannels = settledChannels("tc-second-");
        int thirdChannels = settledChannels("tc-third-");

        System.out.printf("[REAL BROKER] channels per connection: default domain (app)=%d, second=%d, third=%d "
                        + "(one sender = %d channels)%n",
                appChannels, secondChannels, thirdChannels, CHANNELS_PER_SENDER);

        // Each domain owns exactly one sender, and the discard notifier of the three domains reuses the one of the
        // default domain instead of creating its own.
        assertThat(secondChannels).as("channels of the second domain").isEqualTo(CHANNELS_PER_SENDER);
        assertThat(thirdChannels).as("channels of the third domain").isEqualTo(CHANNELS_PER_SENDER);
        assertThat(appChannels).as("channels of the default domain: only its own sender")
                .isEqualTo(CHANNELS_PER_SENDER);
    }

    @Test
    void everyDomainStillNotifiesItsDiscardedMessagesThroughTheSharedNotifier() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        try (Connection connection = factory.newConnection("tc-admin"); Channel admin = connection.createChannel()) {
            String queue = "tc.discard." + UUID.randomUUID();
            admin.exchangeDeclare(DOMAIN_EVENTS_EXCHANGE, "topic", true, false, null);
            admin.queueDeclare(queue, true, false, false, null);
            admin.queueBind(queue, DOMAIN_EVENTS_EXCHANGE, "order.created.dlq");

            Map<String, BrokerProvider> providers = new HashMap<>(manager.getProviders());
            int expected = 0;
            for (Map.Entry<String, BrokerProvider> entry : providers.entrySet()) {
                DiscardNotifier notifier = ((RabbitMQBrokerProvider) entry.getValue()).discardNotifier();
                notifier.notifyDiscard(discardedEvent()).block(Duration.ofSeconds(20));
                expected++;
            }

            int received = 0;
            long deadline = System.currentTimeMillis() + 10_000;
            while (received < expected && System.currentTimeMillis() < deadline) {
                GetResponse response = admin.basicGet(queue, true);
                if (response == null) {
                    Thread.sleep(50);
                } else {
                    received++;
                }
            }
            admin.queueDelete(queue);
            assertThat(received).as("a discard notification per domain reached the broker").isEqualTo(expected);
        }
    }

    private static RabbitMessage discardedEvent() {
        RabbitMessage.RabbitMessageProperties properties = new RabbitMessage.RabbitMessageProperties();
        properties.setContentType("application/json");
        byte[] body = "{\"name\":\"order.created\",\"eventId\":\"1\",\"data\":{\"id\":1}}"
                .getBytes(StandardCharsets.UTF_8);
        return new RabbitMessage(body, properties, "");
    }

    /**
     * Channels that the broker reports for the connection of a domain, once the number stops changing: the flows
     * of a sender obtain their channels asynchronously after the connection is established.
     */
    private static int settledChannels(String connectionNamePrefix) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        int last = -1;
        long stableSince = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            int current = channelsOf(connectionNamePrefix).orElse(-1);
            if (current != last) {
                last = current;
                stableSince = System.currentTimeMillis();
            } else if (current > 0 && System.currentTimeMillis() - stableSince >= 3_000) {
                return current;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("The channels of '" + connectionNamePrefix + "' never settled, last=" + last);
    }

    private static Optional<Integer> channelsOf(String connectionNamePrefix) throws Exception {
        // client_properties holds the client-provided connection name; columns are tab separated
        ExecResult result = RABBIT.execInContainer("rabbitmqctl", "-q", "list_connections", "client_properties",
                "channels");
        // a domain has a sender and a listener connection: its channels are the sum of both
        Optional<Integer> channels = Optional.empty();
        for (String line : result.getStdout().split("\n")) {
            String[] columns = line.trim().split("\t");
            if (columns.length >= 2 && line.contains("\"" + connectionNamePrefix)) {
                int connectionChannels = Integer.parseInt(columns[columns.length - 1].trim());
                channels = Optional.of(channels.orElse(0) + connectionChannels);
            }
        }
        return channels;
    }
}
