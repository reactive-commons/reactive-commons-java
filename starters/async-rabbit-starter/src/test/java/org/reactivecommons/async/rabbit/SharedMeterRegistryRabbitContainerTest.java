package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.HandlerRegistry;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Starts an application twice against a REAL RabbitMQ broker with a {@link MeterRegistry} that outlives it (e.g. one
 * provided by a parent context, or kept across a DevTools restart), and checks that the gauges of the second run
 * report the second run, and that the meters of a closed application are removed from the registry.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class SharedMeterRegistryRabbitContainerTest {

    private static final String QUEUE = "smr-app.subsEvents";
    private static final MeterRegistry SHARED = new SimpleMeterRegistry();

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @Configuration
    static class SharedRegistryHandlers {
        // not closed with the context: it outlives it
        @Bean(destroyMethod = "")
        MeterRegistry meterRegistry() {
            return SHARED;
        }

        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register().listenEvent("scenario.event", event -> Mono.empty(), Map.class);
        }
    }

    @Test
    void theGaugesOfARestartedApplicationReportItAndTheMetersOfAClosedOneAreRemoved() throws Exception {
        try (AnnotationConfigApplicationContext ignored = start()) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, QUEUE);
        }

        try (AnnotationConfigApplicationContext second = start()) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, QUEUE);
            double consumerActive = gauge("reactive_commons.consumer.active", "queue", QUEUE);
            double confirmFlowsLive = gauge("reactive_commons.publish.flow.live", "kind", "confirm");
            System.out.printf("[REAL BROKER] second run: consumer.active=%s publish.flow.live{confirm}=%s%n",
                    consumerActive, confirmFlowsLive);
            assertThat(consumerActive).as("consumer.active of the running listener").isEqualTo(1.0);
            assertThat(confirmFlowsLive).as("publish.flow.live of the running sender").isEqualTo(4.0);
        }

        long leftovers = SHARED.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("reactive_commons.consumer.")
                        || meter.getId().getName().startsWith("reactive_commons.publish."))
                .count();
        System.out.printf("[REAL BROKER] consumer/publish meters left in the registry after closing: %d%n", leftovers);
        assertThat(leftovers).as("consumer and publish meters of closed applications").isZero();
    }

    /**
     * Two applications alive at the same time, with the same domain and queues, on the same registry: closing one must
     * not take away the meters of the other, which keeps running.
     */
    @Test
    void closingOneOfTwoRunningApplicationsKeepsTheMetersOfTheOther() throws Exception {
        // the gauges are bound to the objects of the application started first: it is the one closed
        AnnotationConfigApplicationContext closed = start();
        AnnotationConfigApplicationContext remaining = start();
        try {
            RabbitScenarioSupport.awaitConsumers(RABBIT, QUEUE);
            DomainEventBus bus = remaining.getBean(DomainEventBus.class);
            emit(bus, 5);
            double acksBefore = counter("reactive_commons.publish.outcome", "outcome", "ack");
            closed.close();

            emit(bus, 10);
            double acksAfter = counter("reactive_commons.publish.outcome", "outcome", "ack");
            double confirmFlowsLive = gauge("reactive_commons.publish.flow.live", "kind", "confirm");
            double consumerActive = gauge("reactive_commons.consumer.active", "queue", QUEUE);

            System.out.printf("[REAL BROKER] running application after the other closed: publish.outcome{ack} "
                            + "%s -> %s, publish.flow.live{confirm}=%s, consumer.active=%s%n", acksBefore, acksAfter,
                    confirmFlowsLive, consumerActive);
            assertThat(acksAfter - acksBefore).as("acks of the running application counted").isEqualTo(10.0);
            assertThat(confirmFlowsLive).as("publish.flow.live of the running application").isEqualTo(4.0);
            assertThat(consumerActive).as("consumer.active of the running application").isEqualTo(1.0);
        } finally {
            closed.close();
            remaining.close();
        }
        assertThat(SHARED.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("reactive_commons.")
                && !meter.getId().getName().startsWith("reactive_commons.schedulers.")))
                .as("meters left once both applications are closed").isEmpty();
    }

    private static void emit(DomainEventBus bus, int events) {
        Flux.range(0, events).flatMap(i -> Mono.from(bus.emit(new DomainEvent<>("scenario.event",
                UUID.randomUUID().toString(), Map.of("id", i))))).blockLast(Duration.ofSeconds(30));
    }

    private static double counter(String name, String tag, String value) {
        io.micrometer.core.instrument.Counter counter = SHARED.find(name).tag(tag, value).tag("confirm", "true")
                .counter();
        return counter == null ? Double.NaN : counter.count();
    }

    private static AnnotationConfigApplicationContext start() {
        return RabbitScenarioSupport.startWithHandlers(RABBIT, "smr", false, true, SharedRegistryHandlers.class,
                SendEvents.class, ListenEvents.class);
    }

    private static double gauge(String name, String tag, String value) {
        Gauge gauge = SHARED.find(name).tag(tag, value).gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }
}
