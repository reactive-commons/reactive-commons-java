package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Drives a REAL broker: the depth of the consumed queue is exported as a gauge, read with a passive declare. One
 * handler is held so the backlog stays in the queue (the prefetch is 1), then released and the gauge falls to zero.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConsumerQueueDepthRabbitContainerTest {

    private static final String QUEUE = "qd-app.subsEvents";

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @Test
    @Timeout(180)
    void exportsTheDepthOfTheConsumedQueue() throws Exception {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "qd",
                false, true, BlockingHandler.class, SendEvents.class, ListenEvents.class, SmallPrefetch.class)) {
            System.out.println("[REAL BROKER] context started");
            RabbitScenarioSupport.awaitConsumers(RABBIT, QUEUE);
            System.out.println("[REAL BROKER] consumer registered");
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            DomainEventBus eventBus = context.getBean(DomainEventBus.class);

            assertThat(await(() -> depth(registry) == 0.0, Duration.ofSeconds(15)))
                    .as("the gauge is exported and the empty queue reads 0").isTrue();
            System.out.println("[REAL BROKER] gauge exported, empty queue depth=0");

            for (int i = 0; i < 5; i++) {
                Mono.from(eventBus.emit(new DomainEvent<>("scenario.event", "qd-" + i + "-" + UUID.randomUUID(),
                        Map.of("i", i)))).block(Duration.ofSeconds(10));
            }
            System.out.println("[REAL BROKER] published 5 events");

            // prefetch 1: one delivery is held by the handler, the other four stay ready in the queue
            assertThat(await(() -> depth(registry) >= 1.0, Duration.ofSeconds(15)))
                    .as("the backlog in the queue is exported, depth was " + depth(registry)).isTrue();
            System.out.println("[REAL BROKER] backlog exported, depth=" + depth(registry));

            BlockingHandler.RELEASE.countDown();

            assertThat(await(() -> depth(registry) == 0.0, Duration.ofSeconds(15)))
                    .as("the queue drains once the handler is released").isTrue();
            System.out.println("[REAL BROKER] queue drained");
        }
    }

    private static double depth(MeterRegistry registry) {
        Gauge gauge = registry.find("reactive_commons.consumer.queue.depth")
                .tags("domain", RabbitScenarioSupport.DEFAULT_DOMAIN, "queue", QUEUE).gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }

    private static boolean await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200);
        }
        return condition.getAsBoolean();
    }

    @Configuration
    static class BlockingHandler {
        static final CountDownLatch RELEASE = new CountDownLatch(1);

        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenEvent("scenario.event", event -> Mono.fromRunnable(() -> {
                        try {
                            RELEASE.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).subscribeOn(Schedulers.boundedElastic()).then(), Map.class);
        }
    }

    @Configuration
    static class SmallPrefetch {
        @Bean
        AsyncPropsDomain.RabbitPropsCustomizer oneDeliveryAtATime() {
            return domainProperties -> domainProperties.customize(RabbitScenarioSupport.DEFAULT_DOMAIN,
                    props -> props.setPrefetchCount(1));
        }
    }
}
