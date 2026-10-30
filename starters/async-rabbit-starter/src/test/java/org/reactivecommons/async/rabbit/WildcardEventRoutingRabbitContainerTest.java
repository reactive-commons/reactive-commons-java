package org.reactivecommons.async.rabbit;

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
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Emits events to a REAL RabbitMQ broker that several handlers listen to through wildcards: each event must be
 * handled once, by the most specific handler (an exact name, then {@code *}, then {@code #}).
 * The test is skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class WildcardEventRoutingRabbitContainerTest {

    private static final Map<String, List<String>> HANDLED = new ConcurrentHashMap<>();

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @Configuration
    static class WildcardHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenEvent("wcr.order.created", event -> record(event, "exact"), Map.class)
                    .listenEvent("wcr.*.created", event -> record(event, "single word"), Map.class)
                    .listenEvent("wcr.#", event -> record(event, "multiple words"), Map.class);
        }

        private static Mono<Void> record(DomainEvent<?> event, String handler) {
            HANDLED.computeIfAbsent(event.getName(), name -> new CopyOnWriteArrayList<>()).add(handler);
            return Mono.empty();
        }
    }

    @Test
    void eachEventIsHandledOnceByTheMostSpecificHandler() {
        HANDLED.clear();
        List<String> events = List.of("wcr.order.created", "wcr.user.created", "wcr.user.deleted", "wcr.a.b.c");
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "wcr",
                false, false, WildcardHandlers.class, SendEvents.class, ListenEvents.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, "wcr-app.subsEvents");
            DomainEventBus bus = context.getBean(DomainEventBus.class);

            for (String name : events) {
                Mono.from(bus.emit(new DomainEvent<>(name, UUID.randomUUID().toString(), Map.of())))
                        .block(Duration.ofSeconds(5));
            }
            await().atMost(Duration.ofSeconds(15)).until(() -> HANDLED.keySet().containsAll(events));
            // a late duplicate delivery would show up meanwhile
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> true);

            System.out.printf("[REAL BROKER] handlers per event: %s%n", HANDLED);
            assertThat(HANDLED).containsOnly(
                    Map.entry("wcr.order.created", List.of("exact")),
                    Map.entry("wcr.user.created", List.of("single word")),
                    Map.entry("wcr.user.deleted", List.of("multiple words")),
                    Map.entry("wcr.a.b.c", List.of("multiple words")));
        }
    }
}
