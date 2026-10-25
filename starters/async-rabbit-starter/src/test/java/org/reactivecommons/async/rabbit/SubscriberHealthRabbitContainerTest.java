package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Drives a REAL RabbitMQ broker scenario where a listener's queue is replaced mid-run and checks that the
 * subscriber-side liveness surfaces to the health indicator and the metrics - mirroring what we already assert
 * for the publisher side.
 */
@Testcontainers(disabledWithoutDocker = true)
class SubscriberHealthRabbitContainerTest {

    private static final Duration INACTIVE_GRACE = Duration.ofSeconds(2);
    private static final String QUEUE = "sub-app.subsEvents";

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    /**
     * The queue of a listener is deleted and re-created by someone else with other arguments: the broker cancels the
     * consumer, and every re-subscription fails to declare the queue ({@code PRECONDITION_FAILED}). The health is
     * DOWN with the broken queue, the restarts are counted with their reason, and once the conflicting queue is gone
     * the listener re-declares it, consumes again and the health is UP.
     */
    @Test
    void reportsDownWhileTheQueueCannotBeConsumedAndUpOnceTheListenerConsumesAgain() throws Exception {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "sub",
                false, true, RabbitScenarioSupport.Handlers.class,
                SendEvents.class, ListenEvents.class, PropsOverride.class);
             com.rabbitmq.client.Connection admin = adminConnection()) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, QUEUE);
            RabbitMQBrokerProvider provider = (RabbitMQBrokerProvider) context.getBean(ConnectionManager.class)
                    .getProviders().get(RabbitScenarioSupport.DEFAULT_DOMAIN);
            MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
            assertThat(provider.healthCheck().block().status()).isEqualTo(RCHealth.Status.UP);
            assertThat(gaugeValue(meterRegistry, "reactive_commons.consumer.active")).isEqualTo(1.0);

            // the queue is replaced by one with other arguments: the listener can no longer declare it
            com.rabbitmq.client.Channel channel = admin.createChannel();
            channel.queueDelete(QUEUE);
            channel.queueDeclare(QUEUE, true, false, false, Map.of("x-max-length", 1));

            RCHealth down = awaitStatus(provider, RCHealth.Status.DOWN, Duration.ofSeconds(15));
            System.out.println("[REAL BROKER] listener whose queue cannot be declared: " + down.details());
            assertThat(down.details().get("brokenListeners")).asList().contains(QUEUE);
            assertThat(restarts(meterRegistry, "cancel")).as("the broker cancelled the consumer")
                    .isGreaterThanOrEqualTo(1);
            assertThat(restarts(meterRegistry, "error")).as("the re-subscriptions fail").isGreaterThanOrEqualTo(1);
            assertThat(gaugeValue(meterRegistry, "reactive_commons.consumer.active")).isZero();

            // the conflicting queue goes away: the listener declares its own one and consumes again
            channel.queueDelete(QUEUE);
            awaitStatus(provider, RCHealth.Status.UP, Duration.ofSeconds(30));
            assertThat(gaugeValue(meterRegistry, "reactive_commons.consumer.active")).isEqualTo(1.0);
        }
    }

    private static RCHealth awaitStatus(RabbitMQBrokerProvider provider, RCHealth.Status expected, Duration timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        RCHealth health = provider.healthCheck().block();
        while (health.status() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            health = provider.healthCheck().block();
        }
        assertThat(health.status()).as("health details: %s", health.details()).isEqualTo(expected);
        return health;
    }

    private static double restarts(MeterRegistry registry, String reason) {
        return registry.find("reactive_commons.consumer.restart").tag("queue", QUEUE).tag("reason", reason)
                .counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private static com.rabbitmq.client.Connection adminConnection() throws Exception {
        com.rabbitmq.client.ConnectionFactory factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        return factory.newConnection("sub-admin");
    }

    private static double gaugeValue(MeterRegistry registry, String name) {
        var gauge = registry.find(name).gauge();
        return gauge == null ? 0.0 : gauge.value();
    }

    @Configuration
    static class PropsOverride {
        @Bean
        AsyncPropsDomain.RabbitPropsCustomizer shortGraceCustomizer() {
            return domainProperties -> domainProperties.customize(RabbitScenarioSupport.DEFAULT_DOMAIN, props -> {
                props.setSubscriberInactiveGrace(INACTIVE_GRACE);
            });
        }
    }
}
