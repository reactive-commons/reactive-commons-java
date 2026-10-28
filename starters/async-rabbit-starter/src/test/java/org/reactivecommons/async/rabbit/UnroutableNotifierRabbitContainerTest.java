package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageHandler;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageNotifier;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Publishes mandatory events that the REAL RabbitMQ broker returns as unroutable, and checks that:
 * <ul>
 *     <li>a handler that starts listening to the unroutable messages after another one (e.g. the default processor)
 *     receives them;</li>
 *     <li>the handlers run off the I/O threads of the connections (the Netty event loop shared by every connection
 *     of the JVM): a handler that blocks must not block them.</li>
 * </ul>
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class UnroutableNotifierRabbitContainerTest {

    private static final Map<String, Object> MANDATORY = Map.of("app.async.app.mandatory", "true");
    private static final Duration BLOCKING = Duration.ofSeconds(3);

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private static final List<String> HANDLER_THREADS = new CopyOnWriteArrayList<>();

    /**
     * Records the unroutable messages it receives, and blocks its thread as a handler calling a blocking client does.
     */
    static final UnroutableMessageHandler BLOCKING_HANDLER = result -> Mono.fromRunnable(() -> {
        HANDLER_THREADS.add(Thread.currentThread().getName());
        try {
            Thread.sleep(BLOCKING.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    });

    @Configuration
    static class Handlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register().listenEvent("scenario.event", event -> Mono.empty(), Map.class);
        }
    }

    /**
     * Registers the handler as the guide shows. Registered before the configuration of the starter, it replaces the
     * default processor: the handler is the only one listening.
     */
    @Configuration
    static class HandlersWithOwnUnroutableHandler extends Handlers {
        @Bean
        UnroutableMessageProcessor registerUnroutableMessageHandler(UnroutableMessageNotifier notifier) {
            notifier.listenToUnroutableMessages(BLOCKING_HANDLER);
            return new UnroutableMessageProcessor();
        }
    }

    @Test
    void aHandlerThatStartsListeningAfterAnotherOneReceivesTheUnroutableMessages() throws Exception {
        HANDLER_THREADS.clear();
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "unr",
                false, false, MANDATORY, Handlers.class, SendEvents.class, ListenEvents.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, "unr-app.subsEvents");
            // the default processor is already listening
            context.getBean(UnroutableMessageNotifier.class).listenToUnroutableMessages(BLOCKING_HANDLER);

            emitUnroutable(context.getBean(DomainEventBus.class));

            await().atMost(Duration.ofSeconds(10)).until(() -> !HANDLER_THREADS.isEmpty());
            System.out.printf("[REAL BROKER] unroutable messages received by the handler listening second: %d%n",
                    HANDLER_THREADS.size());
            assertThat(HANDLER_THREADS).as("unroutable messages received by the handler listening second")
                    .hasSize(1);
        }
    }

    @Test
    void aBlockingHandlerRunsOffTheIoThreadsOfTheConnections() throws Exception {
        HANDLER_THREADS.clear();
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "unb",
                false, false, MANDATORY, HandlersWithOwnUnroutableHandler.class, SendEvents.class,
                ListenEvents.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, "unb-app.subsEvents");
            DomainEventBus bus = context.getBean(DomainEventBus.class);

            // not waited for: the publisher of the returned event must not wait for the handler either
            long unroutableStart = System.nanoTime();
            AtomicLong unroutableMillis = new AtomicLong(-1);
            Mono.from(bus.emit(new DomainEvent<>("nobody.listens", UUID.randomUUID().toString(), Map.of())))
                    .onErrorResume(error -> Mono.empty())
                    .doFinally(signal -> unroutableMillis.set(
                            Duration.ofNanos(System.nanoTime() - unroutableStart).toMillis()))
                    .subscribe();
            await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(5))
                    .until(() -> !HANDLER_THREADS.isEmpty());
            // the handler is now blocking: the confirms of these events must not wait for it
            long start = System.nanoTime();
            Flux.range(0, 20)
                    .flatMap(i -> Mono.from(bus.emit(new DomainEvent<>("scenario.event", UUID.randomUUID().toString(),
                            Map.of("id", i)))))
                    .blockLast(Duration.ofSeconds(30));
            long confirmsMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(5))
                    .until(() -> unroutableMillis.get() >= 0);

            System.out.printf("[REAL BROKER] blocking unroutable handler ran on %s; returned event completed in %d "
                    + "ms; 20 events confirmed in %d ms%n", HANDLER_THREADS, unroutableMillis.get(), confirmsMillis);
            assertThat(HANDLER_THREADS).as("threads that ran the unroutable handler").singleElement()
                    .satisfies(thread -> assertThat(thread).doesNotStartWith("multiThreadIoEventLoopGroup")
                            .doesNotStartWith("AMQP Connection"));
            assertThat(unroutableMillis.get()).as("time for the returned event to complete while the handler blocks")
                    .isBetween(0L, BLOCKING.toMillis() / 2);
            assertThat(confirmsMillis).as("time to confirm 20 events while the handler blocks")
                    .isLessThan(BLOCKING.toMillis() / 2);
        }
    }

    private static void emitUnroutable(DomainEventBus bus) {
        Mono.from(bus.emit(new DomainEvent<>("nobody.listens", UUID.randomUUID().toString(), Map.of())))
                .onErrorResume(error -> Mono.empty()) // returned by the broker: only its notification matters here
                .block(Duration.ofSeconds(30));
    }
}
