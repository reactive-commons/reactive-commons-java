package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.HandlerRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * When the listeners start consuming, on a REAL RabbitMQ broker: like the listener containers of Spring AMQP, once the
 * application context created and initialized every bean, never while it is being refreshed.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ListenerStartupRabbitContainerTest {

    private static final String EVENT = "startup.event";
    private static final AtomicBoolean initialized = new AtomicBoolean();
    private static volatile CompletableFuture<Boolean> initializedWhenHandled = new CompletableFuture<>();

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @Configuration
    static class StartupHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenEvent(EVENT, event -> Mono.fromRunnable(() ->
                            initializedWhenHandled.complete(initialized.get())), Map.class);
        }
    }

    /**
     * A bean that completes its initialization once every bean was created (e.g. warming up a cache).
     */
    @Configuration
    static class LateInitialization {
        @Bean
        SmartInitializingSingleton warmUp() {
            // a listener consuming meanwhile would handle the waiting message while it initializes
            return () -> {
                await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))
                        .until(() -> !initializedWhenHandled.isDone());
                initialized.set(true);
            };
        }
    }

    @Test
    void aMessageWaitingInTheQueueIsHandledOnlyOnceTheApplicationIsInitialized() throws Exception {
        initialized.set(false);
        initializedWhenHandled = new CompletableFuture<>();
        // the queue of the listener exists, and a message waits in it while the application is down
        try (AnnotationConfigApplicationContext listener = RabbitScenarioSupport.startWithHandlers(RABBIT, "boot",
                false, false, StartupHandlers.class, ListenEvents.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, "boot-app.subsEvents");
        }
        try (AnnotationConfigApplicationContext publisher = RabbitScenarioSupport.startWithHandlers(RABBIT,
                "boot-publisher", false, false, StartupHandlers.class, SendEvents.class)) {
            Mono.from(publisher.getBean(DomainEventBus.class).emit(new DomainEvent<>(EVENT,
                    UUID.randomUUID().toString(), Map.of("k", "v")))).block(Duration.ofSeconds(10));
        }

        try (AnnotationConfigApplicationContext application = RabbitScenarioSupport.startWithHandlers(RABBIT, "boot",
                false, false, StartupHandlers.class, ListenEvents.class, LateInitialization.class)) {
            boolean initializedFirst = initializedWhenHandled.get(20, TimeUnit.SECONDS);
            System.out.println("[REAL BROKER] waiting message handled with the application initialized="
                    + initializedFirst);

            assertThat(initializedFirst).as("handled once every bean is initialized").isTrue();
        }
    }
}
