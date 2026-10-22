package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.exceptions.SendFailureBrokerBlockedException;
import org.reactivecommons.async.rabbit.communications.ConnectionBlockedState;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Exercises the full sender/health/metrics plumbing of a {@code connection.blocked} alarm against a REAL
 * RabbitMQ broker. The broker-side alarm propagation itself (memory/disk watermark triggers) is finicky across
 * RabbitMQ versions and container runtimes, so this test drives the {@link ConnectionBlockedState} directly
 * on the live sender - which is exactly what the client's own {@link com.rabbitmq.client.BlockedListener}
 * does when the broker emits the {@code connection.blocked} frame. That listener wiring is a standard
 * RabbitMQ client contract, verified separately by {@code ConnectionBlockedStateTest}.
 * The test is skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConnectionBlockedRabbitContainerTest {

    private static final Duration HEALTH_GRACE = Duration.ofSeconds(1);

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

    @Test
    void failsFastWhenBlockedStateIsRaisedAndClearsOnUnblock() throws Exception {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "blk",
                false, true, RabbitScenarioSupport.Handlers.class,
                SendEvents.class, ListenEvents.class, PropsOverride.class)) {
            // wait for the event queue to have a consumer: the exchange + queue + binding exist before we publish
            RabbitScenarioSupport.awaitConsumers(RABBIT, "blk-app.subsEvents");
            RabbitMQBrokerProvider provider = (RabbitMQBrokerProvider) context.getBean(ConnectionManager.class)
                    .getProviders().get(RabbitScenarioSupport.DEFAULT_DOMAIN);
            MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
            DomainEventBus bus = context.getBean(DomainEventBus.class);
            ConnectionBlockedState state = provider.sender().blockedState();

            // publishing works before any alarm
            Mono.from(bus.emit(new DomainEvent<>("scenario.event", UUID.randomUUID().toString(), Map.of("i", 0))))
                    .block(Duration.ofSeconds(10));
            assertThat(state.isBlocked()).isFalse();
            assertThat(gaugeValue(meterRegistry, "reactive_commons.publish.connection.blocked.state"))
                    .isZero();
            assertThat(provider.healthCheck().block().status()).isEqualTo(RCHealth.Status.UP);

            // simulate the connection.blocked frame arriving from the broker (what BlockedListener does)
            state.onBlocked("low on memory");

            // while blocked, a publish fails fast rather than waiting for sendTimeout
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> Mono.from(bus.emit(new DomainEvent<>("scenario.event",
                    UUID.randomUUID().toString(), Map.of("i", 1)))).block(Duration.ofSeconds(5)))
                    .hasCauseInstanceOf(SendFailureBrokerBlockedException.class);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
            assertThat(elapsedMs).as("fail-fast duration").isLessThan(1_000);

            // health reports DOWN with the blocked reason once the grace elapses
            Thread.sleep(HEALTH_GRACE.plusMillis(500).toMillis());
            RCHealth health = provider.healthCheck().block();
            assertThat(health).isNotNull();
            assertThat(health.status()).isEqualTo(RCHealth.Status.DOWN);
            assertThat(health.details()).containsKey("connectionBlockedSince");
            assertThat(health.details()).containsEntry("connectionBlockedReason", "low on memory");

            // gauge is 1 and the function counter has incremented
            assertThat(gaugeValue(meterRegistry, "reactive_commons.publish.connection.blocked.state"))
                    .isEqualTo(1.0);
            assertThat(functionCounterValue(meterRegistry, "reactive_commons.publish.connection.blocked"))
                    .isGreaterThanOrEqualTo(1.0);

            // simulate connection.unblocked arriving from the broker
            state.onUnblocked();

            Mono.from(bus.emit(new DomainEvent<>("scenario.event", UUID.randomUUID().toString(), Map.of("i", 2))))
                    .block(Duration.ofSeconds(10));
            assertThat(gaugeValue(meterRegistry, "reactive_commons.publish.connection.blocked.state"))
                    .isZero();
            assertThat(provider.healthCheck().block().status()).isEqualTo(RCHealth.Status.UP);
        }
    }

    private static double gaugeValue(MeterRegistry registry, String name) {
        var gauge = registry.find(name).gauge();
        return gauge == null ? 0.0 : gauge.value();
    }

    private static double functionCounterValue(MeterRegistry registry, String name) {
        var fc = registry.find(name).functionCounter();
        return fc == null ? 0.0 : fc.count();
    }

    @Configuration
    static class PropsOverride {
        @Bean
        AsyncPropsDomain.RabbitPropsCustomizer blockedHealthGraceCustomizer() {
            return domainProperties -> domainProperties.customize(RabbitScenarioSupport.DEFAULT_DOMAIN, props ->
                    props.setBlockedHealthGrace(HEALTH_GRACE));
        }
    }
}
