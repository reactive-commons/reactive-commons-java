package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Runs the starter against a REAL RabbitMQ broker with large messages, and checks that the logs identify the failing
 * messages and write only the beginning of their body (a body can weigh up to 16 MiB, and a failed delivery is logged
 * again on every retry):
 * <ul>
 *     <li>a delivery whose handler fails, retried again and again;</li>
 *     <li>a mandatory event returned by the broker as unroutable.</li>
 * </ul>
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class MessageContentLoggingRabbitContainerTest {

    private static final String END_OF_BODY = "END-OF-BODY";
    /**
     * Ordered, so the marker is at the end of the serialized body, far beyond its first KiB.
     */
    private static final Map<String, Object> LARGE_DATA = new LinkedHashMap<>(Map.of("id", "large"));

    static {
        LARGE_DATA.put("padding", "x".repeat(64 * 1024) + END_OF_BODY);
    }
    private static final Map<String, Object> PROPERTIES = Map.of(
            "app.async.app.retryDelay", "200",
            "app.async.app.mandatory", "true");

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private static final AtomicInteger FAILURES = new AtomicInteger();

    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        private final SimpleFormatter formatter = new SimpleFormatter();

        @Override
        public void publish(LogRecord logRecord) {
            logs.add(formatter.format(logRecord));
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    };

    @Configuration
    static class FailingHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenEvent("scenario.event", event -> {
                        FAILURES.incrementAndGet();
                        return Mono.error(new IllegalStateException("the handler always fails"));
                    }, Map.class);
        }
    }

    @BeforeEach
    void captureLogs() {
        capture.setLevel(Level.ALL);
        Logger.getLogger("").addHandler(capture);
    }

    @AfterEach
    void stopCapturing() {
        Logger.getLogger("").removeHandler(capture);
    }

    @Test
    void theLogsIdentifyFailingMessagesAndHoldOnlyTheBeginningOfTheirBody() throws Exception {
        FAILURES.set(0);
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "mcl",
                false, false, PROPERTIES, FailingHandlers.class, SendEvents.class, ListenEvents.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, "mcl-app.subsEvents");
            DomainEventBus bus = context.getBean(DomainEventBus.class);
            String failingId = UUID.randomUUID().toString();
            String unroutableId = UUID.randomUUID().toString();

            Mono.from(bus.emit(new DomainEvent<>("scenario.event", failingId, LARGE_DATA)))
                    .block(Duration.ofSeconds(30));
            Mono.from(bus.emit(new DomainEvent<>("nobody.listens", unroutableId, LARGE_DATA)))
                    .onErrorResume(error -> Mono.empty()) // returned by the broker: only its log matters here
                    .block(Duration.ofSeconds(30));

            long deadline = System.currentTimeMillis() + 30_000;
            while ((FAILURES.get() < 3 || logs.stream().noneMatch(line -> line.contains(unroutableId)))
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }

            List<String> withWholeBody = logs.stream().filter(line -> line.contains(END_OF_BODY)).toList();
            System.out.printf("[REAL BROKER] handler failures=%d, log records=%d, records with the whole body=%d, "
                            + "largest record=%d chars%n", FAILURES.get(), logs.size(), withWholeBody.size(),
                    logs.stream().mapToInt(String::length).max().orElse(0));

            assertThat(FAILURES.get()).as("deliveries retried after their handler failed").isGreaterThanOrEqualTo(3);
            assertThat(logs).as("the failing delivery is identified").anyMatch(line -> line.contains(failingId));
            assertThat(logs).as("the unroutable event is identified").anyMatch(line -> line.contains(unroutableId));
            assertThat(withWholeBody).as("log records holding the whole body of a message").isEmpty();
        }
    }
}
