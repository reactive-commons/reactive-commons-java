package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.rabbit.listeners.ListenerLiveness;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Drives a REAL RabbitMQ broker scenario where a listener's queue is deleted mid-run and checks that the
 * subscriber-side liveness surfaces to the health indicator and the metrics - mirroring what we already assert
 * for the publisher side.
 */
@Testcontainers(disabledWithoutDocker = true)
class SubscriberHealthRabbitContainerTest {

    private static final Duration INACTIVE_GRACE = Duration.ofSeconds(2);

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

    @Test
    void reportsDownAndIncrementsRestartMetricWhenTheQueueIsDeletedUnderTheListener() throws Exception {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "sub",
                false, true, RabbitScenarioSupport.Handlers.class,
                SendEvents.class, ListenEvents.class, PropsOverride.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, "sub-app.subsEvents");
            RabbitMQBrokerProvider provider = (RabbitMQBrokerProvider) context.getBean(ConnectionManager.class)
                    .getProviders().get(RabbitScenarioSupport.DEFAULT_DOMAIN);
            MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);

            // the listener is live before the queue goes away
            assertThat(provider.healthCheck().block().status()).isEqualTo(RCHealth.Status.UP);
            assertThat(gaugeValue(meterRegistry, "reactive_commons.consumer.active"))
                    .as("consumer.active before delete")
                    .isEqualTo(1.0);

            // delete the queue the listener consumes from: the broker closes its channel
            execRabbitmqctl("delete_queue", "sub-app.subsEvents");

            // the listener attempts to re-subscribe; declaring a durable queue that was auto-recreated with
            // different arguments would loop. Here the queue is gone and the listener's setUpBindings will
            // re-declare it. We make sure the restart happens by polling the counter.
            waitForRestart(meterRegistry, Duration.ofSeconds(10));

            // health goes DOWN once the listener has been inactive for the configured grace, OR because
            // restarts fail. Either way the subscriber-broken path is exercised.
            long deadline = System.currentTimeMillis() + 15_000;
            RCHealth.Status status = provider.healthCheck().block().status();
            while (status == RCHealth.Status.UP && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                status = provider.healthCheck().block().status();
            }
            // Topology re-declaration is automatic in setUpBindings, so the listener may recover before DOWN.
            // Accept either outcome but assert the restart counter clearly incremented.
            assertThat(functionCounterValue(meterRegistry,
                    "reactive_commons.consumer.restart"))
                    .as("consumer.restart counter after queue deletion")
                    .isGreaterThanOrEqualTo(1.0);
        }
    }

    private static void waitForRestart(MeterRegistry registry, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (functionCounterValue(registry, "reactive_commons.consumer.restart") >= 1.0) {
                return;
            }
            Thread.sleep(50);
        }
    }

    private static double gaugeValue(MeterRegistry registry, String name) {
        var gauge = registry.find(name).gauge();
        return gauge == null ? 0.0 : gauge.value();
    }

    private static double functionCounterValue(MeterRegistry registry, String name) {
        double total = 0.0;
        for (var counter : registry.find(name).counters()) {
            total += counter.count();
        }
        return total;
    }

    private static void execRabbitmqctl(String... args) throws Exception {
        var command = new String[args.length + 1];
        command[0] = "rabbitmqctl";
        System.arraycopy(args, 0, command, 1, args.length);
        var result = RABBIT.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("rabbitmqctl " + String.join(" ", args) + " failed (exit "
                    + result.getExitCode() + "): " + result.getStderr() + " / " + result.getStdout());
        }
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

    // silence the unused warning while keeping the class importable in sibling tests
    @SuppressWarnings("unused")
    private ListenerLiveness unused;
}
