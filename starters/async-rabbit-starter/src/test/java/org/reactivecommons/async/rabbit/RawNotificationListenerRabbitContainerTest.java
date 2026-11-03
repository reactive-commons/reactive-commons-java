package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.config.annotations.EnableNotificationListener;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Listens to a notification with a raw handler ({@link HandlerRegistry#listenNotificationRawEvent}) on a REAL
 * RabbitMQ broker: the handler must receive the notifications, as the raw handlers of the events do.
 * The test is skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class RawNotificationListenerRabbitContainerTest {

    private static final String NOTIFICATION = "scenario.notification";
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final List<Object> RECEIVED = new CopyOnWriteArrayList<>();

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @Configuration
    static class RawNotificationHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenNotificationRawEvent(NOTIFICATION, message -> {
                        RECEIVED.add(message);
                        return Mono.empty();
                    });
        }
    }

    @Configuration
    @EnableNotificationListener
    static class ListenNotifications {
    }

    @Test
    void aRawNotificationHandlerReceivesTheNotifications() {
        RECEIVED.clear();
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "rnl",
                false, false, RawNotificationHandlers.class, SendEvents.class, ListenNotifications.class)) {
            DomainEventBus bus = context.getBean(DomainEventBus.class);

            // emitted again until one is handled: the notification queue may not be bound yet
            Throwable timeout = catchThrowable(() -> await().atMost(WAIT)
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        Mono.from(bus.emit(new DomainEvent<>(NOTIFICATION, UUID.randomUUID().toString(),
                                Map.of("raw", true)))).block(Duration.ofSeconds(5));
                        return !RECEIVED.isEmpty();
                    }));

            System.out.printf("[REAL BROKER] notifications received by the raw handler within %d s: %d%n",
                    WAIT.toSeconds(), RECEIVED.size());
            assertThat(timeout).as("a notification received by the raw handler").isNull();
        }
    }
}
