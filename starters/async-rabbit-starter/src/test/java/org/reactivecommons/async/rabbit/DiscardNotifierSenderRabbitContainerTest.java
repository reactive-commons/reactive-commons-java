package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
@DirtiesContext
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
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

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
    void discardNotifiersDoNotOpenExtraSendersOnTheDefaultDomainConnection() {
        assertThat(manager.getProviders()).containsOnlyKeys("app", "second", "third");

        int appChannels = RabbitScenarioSupport.settledChannels(RABBIT, "tc-app-");
        int secondChannels = RabbitScenarioSupport.settledChannels(RABBIT, "tc-second-");
        int thirdChannels = RabbitScenarioSupport.settledChannels(RABBIT, "tc-third-");

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

            int notifications = expected;
            AtomicInteger received = new AtomicInteger();
            try {
                await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).until(() -> {
                    while (admin.basicGet(queue, true) != null) {
                        received.incrementAndGet();
                    }
                    return received.get() >= notifications;
                });
            } finally {
                admin.queueDelete(queue);
            }
            assertThat(received.get()).as("a discard notification per domain reached the broker")
                    .isEqualTo(notifications);
        }
    }

    private static RabbitMessage discardedEvent() {
        RabbitMessage.RabbitMessageProperties properties = new RabbitMessage.RabbitMessageProperties();
        properties.setContentType("application/json");
        byte[] body = "{\"name\":\"order.created\",\"eventId\":\"1\",\"data\":{\"id\":1}}"
                .getBytes(StandardCharsets.UTF_8);
        return new RabbitMessage(body, properties, "");
    }

}
