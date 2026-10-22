package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.rabbit.listeners.ListenerSchedulers;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenCommands;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenQueries;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Runs the listeners of the starter against a REAL RabbitMQ broker and looks at the threads that execute the
 * handlers:
 * <ul>
 *     <li>how many handlers really run at the same time when they block (JDBC, a blocking client...), with the
 *     default {@code maxConcurrency} of 250;</li>
 *     <li>how many threads the listeners create (every listener used to have its own pools);</li>
 *     <li>whether those threads are released when the application closes.</li>
 * </ul>
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ListenerSchedulersRabbitContainerTest {

    /**
     * Threads of the handlers executors, with the name they had before (one pair of pools per listener) and after
     * being shared by the whole application.
     */
    private static final Pattern HANDLER_THREADS = Pattern.compile(
            "^(Application\\w*Listener|errorReporterScheduler|rc-handlers|rc-error-reporter).*");
    private static final long BLOCKING_MILLIS = 200;
    private static final int BLOCKING_COMMANDS = 1_000;

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

    private static final AtomicInteger RUNNING = new AtomicInteger();
    private static final AtomicInteger PEAK = new AtomicInteger();
    private static volatile CountDownLatch handled = new CountDownLatch(0);
    private static volatile long blockMillis = BLOCKING_MILLIS;

    /**
     * Command handler that blocks its thread, as a handler that uses a blocking client does.
     */
    @Configuration
    static class BlockingHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenEvent("scenario.event", event -> {
                        handled.countDown();
                        return Mono.empty();
                    }, Map.class)
                    .handleCommand("scenario.command", command -> Mono.fromRunnable(() -> {
                        int running = RUNNING.incrementAndGet();
                        PEAK.accumulateAndGet(running, Math::max);
                        try {
                            if (blockMillis > 0) {
                                Thread.sleep(blockMillis);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            RUNNING.decrementAndGet();
                            handled.countDown();
                        }
                    }), Map.class)
                    .serveQuery("scenario.query", query -> Mono.just("ok"),
                            String.class);
        }
    }

    @Test
    void blockingHandlersRunWithTheConfiguredConcurrencyInsteadOfTheSizeOfThePool() throws Exception {
        PEAK.set(0);
        blockMillis = BLOCKING_MILLIS;
        handled = new CountDownLatch(BLOCKING_COMMANDS);
        try (AnnotationConfigApplicationContext context = start("m3a")) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, "m3a-app");
            DirectAsyncGateway gateway = context.getBean(DirectAsyncGateway.class);
            long start = System.nanoTime();
            Flux.range(0, BLOCKING_COMMANDS)
                    .flatMap(i -> gateway.sendCommand(new Command<>("scenario.command", UUID.randomUUID().toString(),
                            Map.of("id", i)), "m3a-app"), 100)
                    .blockLast(Duration.ofSeconds(60));
            assertThat(handled.await(90, TimeUnit.SECONDS)).as("every command was handled").isTrue();
            long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();

            System.out.printf("[REAL BROKER] %d commands that block %d ms each (maxConcurrency=250): peak of handlers "
                            + "running at the same time=%d, total time=%d ms (%d msg/s)%n", BLOCKING_COMMANDS,
                    BLOCKING_MILLIS, PEAK.get(), millis, BLOCKING_COMMANDS * 1000L / millis);
            assertThat(PEAK.get()).as("handlers running at the same time").isGreaterThanOrEqualTo(200);
        }
    }

    @Test
    void listenersShareTheirThreadsAndReleaseThemWhenTheApplicationCloses() throws Exception {
        blockMillis = 0; // handlers that do not block
        // the schedulers are shared by every application of the JVM (e.g. the contexts Spring caches for other tests)
        assumeTrue(ListenerSchedulers.activeApplications() == 0,
                "another application of this JVM is using the shared schedulers, they cannot be released");
        int before = handlerThreads().size();
        List<String> whileRunning;
        int warmUpThreads;
        try (AnnotationConfigApplicationContext context = start("m3b")) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, "m3b-app", "m3b-app.query", "m3b-app.subsEvents");
            DirectAsyncGateway gateway = context.getBean(DirectAsyncGateway.class);
            DomainEventBus bus = context.getBean(DomainEventBus.class);
            // cold start: the first messages need class loading, serializers, connections... and take longer
            traffic(bus, gateway, 300);
            warmUpThreads = handlerThreads().size() - before;
            // steady state: every listener keeps receiving traffic
            traffic(bus, gateway, 3_000);
            whileRunning = handlerThreads();
        }
        int createdInSteadyState = whileRunning.size() - before - warmUpThreads;

        // the threads of the application must disappear once it is closed
        long deadline = System.currentTimeMillis() + 10_000;
        List<String> afterClose = handlerThreads();
        while (afterClose.size() > before && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            afterClose = handlerThreads();
        }

        System.out.printf("[REAL BROKER] 3 listeners (events, commands, queries), handlers that do not block: handler "
                        + "threads created: cold start=%d, then %d more with sustained traffic (36 before: 12 per "
                        + "listener); still alive 10 s after closing the application=%d%n", warmUpThreads,
                createdInSteadyState, afterClose.size() - before);
        assertThat(whileRunning).as("no thread belongs to a single listener")
                .noneMatch(name -> name.startsWith("Application"));
        // the starvation monitor may add a few threads when the CPUs are busy elsewhere (e.g. a shared CI runner that
        // builds other modules at the same time); a pool per listener would add 12 per listener, 36 here
        assertThat(createdInSteadyState).as("threads created by sustained traffic of handlers that do not block")
                .isLessThanOrEqualTo(Runtime.getRuntime().availableProcessors() + 8);
        assertThat(afterClose.size() - before).as("threads left after closing the application").isZero();
    }

    private static void traffic(DomainEventBus bus, DirectAsyncGateway gateway, int total) throws Exception {
        handled = new CountDownLatch(2 * total);
        Flux.range(0, total).flatMap(i -> Mono.from(bus.emit(new DomainEvent<>("scenario.event",
                UUID.randomUUID().toString(), Map.of("id", i)))), 50).blockLast(Duration.ofSeconds(60));
        Flux.range(0, total).flatMap(i -> gateway.sendCommand(new Command<>("scenario.command",
                UUID.randomUUID().toString(), Map.of("id", i)), "m3b-app"), 50).blockLast(Duration.ofSeconds(60));
        assertThat(handled.await(90, TimeUnit.SECONDS)).as("every message was handled").isTrue();
        for (int i = 0; i < total / 10; i++) {
            assertThat(gateway.requestReply(new AsyncQuery<>("scenario.query", "x"), "m3b-app", String.class)
                    .block(Duration.ofSeconds(30))).isEqualTo("ok");
        }
    }

    private static AnnotationConfigApplicationContext start(String prefix) {
        return RabbitScenarioSupport.startWithHandlers(RABBIT, prefix, false, true, BlockingHandlers.class, SendEvents.class,
                SendDirect.class, ListenEvents.class, ListenCommands.class, ListenQueries.class);
    }

    private static List<String> handlerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> HANDLER_THREADS.matcher(name).matches())
                .toList();
    }
}
