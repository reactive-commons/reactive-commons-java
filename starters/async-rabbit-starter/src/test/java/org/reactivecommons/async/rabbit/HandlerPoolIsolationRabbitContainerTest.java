package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.commons.config.BrokerConfig;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenQueries;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Runs, on a REAL RabbitMQ broker, an event listener whose handlers block (e.g. a slow JDBC call) with more events
 * than the shared handler pool has threads, and a query listener whose handler answers at once: the queries must be
 * answered within their reply timeout, i.e. a listener whose handlers block must not take the threads the other
 * listeners need.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class HandlerPoolIsolationRabbitContainerTest {

    private static final String TARGET = "hpi-app";
    private static final Duration BLOCKING = Duration.ofSeconds(6);
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(2);
    private static final int BLOCKING_EVENTS = 400;
    private static final int QUERIES = 10;

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private static final AtomicInteger BLOCKED = new AtomicInteger();

    @Configuration
    static class Handlers {
        @Bean
        BrokerConfig brokerConfig() {
            return new BrokerConfig(false, true, true, REPLY_TIMEOUT);
        }

        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenEvent("scenario.event", event -> Mono.fromRunnable(() -> {
                        BLOCKED.incrementAndGet();
                        try {
                            Thread.sleep(BLOCKING.toMillis());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            BLOCKED.decrementAndGet();
                        }
                    }), Map.class)
                    .serveQuery("scenario.query", query -> Mono.just("ok"), String.class);
        }
    }

    @Test
    void blockingHandlersOfOneListenerDoNotDelayTheHandlersOfAnother() throws Exception {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "hpi",
                false, true, Handlers.class, SendEvents.class, ListenEvents.class, SendDirect.class,
                ListenQueries.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, TARGET + ".subsEvents", TARGET + ".query");
            DomainEventBus bus = context.getBean(DomainEventBus.class);
            DirectAsyncGateway gateway = context.getBean(DirectAsyncGateway.class);

            Flux.range(0, BLOCKING_EVENTS)
                    .flatMap(i -> Mono.from(bus.emit(new DomainEvent<>("scenario.event", UUID.randomUUID().toString(),
                            Map.of("id", i)))), 100)
                    .blockLast(Duration.ofSeconds(60));
            await().atMost(Duration.ofSeconds(30)).until(() -> BLOCKED.get() >= 200);
            // the handler pool stays saturated by the blocking handlers
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> BLOCKED.get() >= 200);

            int answered = Flux.range(0, QUERIES)
                    .flatMap(i -> gateway.requestReply(new AsyncQuery<>("scenario.query", "q" + i), TARGET,
                                    String.class)
                            .onErrorResume(error -> Mono.empty()))
                    .count()
                    .block(Duration.ofSeconds(30))
                    .intValue();

            System.out.printf("[REAL BROKER] %d event handlers blocking %d s each (blocked now=%d): queries answered "
                            + "within %d ms = %d/%d%n", BLOCKING_EVENTS, BLOCKING.toSeconds(), BLOCKED.get(),
                    REPLY_TIMEOUT.toMillis(), answered, QUERIES);
            assertThat(answered).as("queries answered within their reply timeout").isEqualTo(QUERIES);
        }
    }
}
