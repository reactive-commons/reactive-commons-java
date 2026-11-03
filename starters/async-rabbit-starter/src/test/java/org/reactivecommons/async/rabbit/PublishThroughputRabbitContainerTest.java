package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.awaitility.core.ConditionTimeoutException;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenCommands;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenQueries;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Publishes domain events with publisher confirms at a sustained 15000 events per second (above the peaks of
 * 10000 seen in production) through the complete starter, against a REAL RabbitMQ broker, and checks that:
 * <ul>
 *     <li>every event is confirmed and reaches the broker, with its latency measured from the moment it was due,
 *     so a slow sender cannot hide behind a slow generator;</li>
 *     <li>the same holds while 1500 discarded messages per second are notified through the shared discard notifier,
 *     which publishes through the sender of the default domain;</li>
 *     <li>the ceiling of the sender (as fast as the broker confirms) is above those 15000 per second.</li>
 * </ul>
 * The application is the busiest one of the documentation: sending, and listening to events, commands and queries,
 * with {@code listenReplies=true}. The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class PublishThroughputRabbitContainerTest {

    private static final String EVENTS_EXCHANGE = "domainEvents";
    private static final String EVENT_NAME = "perf.event";
    private static final String DISCARD_ROUTING_KEY = "order.created.dlq";
    private static final int TARGET_RATE = 15_000;
    private static final int DISCARD_RATE = 1_500;
    private static final int SECONDS = 15;
    private static final String NOTE = "x".repeat(200); // ~300 bytes of JSON per event

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private record Stats(String name, int sent, int confirmed, int failed, double confirmedPerSecond,
                         long p50Ms, long p95Ms, long p99Ms, long maxMs) {
        @Override
        public @NonNull String toString() {
            return String.format("%-9s sent=%d confirmed=%d failed=%d | %.0f confirmed/s | latency ms: p50=%d p95=%d "
                            + "p99=%d max=%d", name, sent, confirmed, failed, confirmedPerSecond, p50Ms, p95Ms,
                    p99Ms, maxMs);
        }
    }

    @Test
    void sustainsTheTargetRateWithAndWithoutDiscardNotifications() throws Exception {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.start(RABBIT, "perf", false, true,
                SendEvents.class, SendDirect.class, ListenEvents.class, ListenCommands.class, ListenQueries.class);
             Connection connection = adminConnection(); Channel admin = connection.createChannel()) {
            DomainEventBus bus = context.getBean(DomainEventBus.class);
            DiscardNotifier discards = ((RabbitMQBrokerProvider) context.getBean(ConnectionManager.class)
                    .getProviders().get(RabbitScenarioSupport.DEFAULT_DOMAIN)).discardNotifier();
            admin.exchangeDeclare(EVENTS_EXCHANGE, "topic", true, false, null);
            String eventsQueue = bind(admin, "perf.events." + UUID.randomUUID(), EVENT_NAME);
            String discardsQueue = bind(admin, "perf.discards." + UUID.randomUUID(), DISCARD_ROUTING_KEY);
            int channels = RabbitScenarioSupport.settledChannels(RABBIT, "perf-app-");

            // warm-up (JIT, connections, queues)
            drive(bus, discards, 3, 2_000, 0);
            admin.queuePurge(eventsQueue);
            admin.queuePurge(discardsQueue);

            // 1. target rate
            Stats[] plain = drive(bus, discards, SECONDS, TARGET_RATE, 0);
            int plainArrived = settledCount(admin, eventsQueue, plain[0].confirmed());
            System.out.printf("[REAL BROKER] %d events/s for %d s, %d channels on the connection%n", TARGET_RATE,
                    SECONDS, channels);
            System.out.println("[REAL BROKER]   " + plain[0] + " | reached the broker=" + plainArrived);
            admin.queuePurge(eventsQueue);

            // 2. target rate while 10% of it is notified as discards
            Stats[] mixed = drive(bus, discards, SECONDS, TARGET_RATE, DISCARD_RATE);
            int mixedEvents = settledCount(admin, eventsQueue, mixed[0].confirmed());
            int mixedDiscards = settledCount(admin, discardsQueue, mixed[1].confirmed());
            System.out.printf("[REAL BROKER] %d events/s + %d discards/s for %d s%n", TARGET_RATE, DISCARD_RATE,
                    SECONDS);
            System.out.println("[REAL BROKER]   " + mixed[0] + " | reached the broker=" + mixedEvents);
            System.out.println("[REAL BROKER]   " + mixed[1] + " | reached the broker=" + mixedDiscards);
            admin.queuePurge(eventsQueue);

            // 3. ceiling: as fast as the broker confirms, 2000 events in flight
            int total = 200_000;
            AtomicInteger failed = new AtomicInteger();
            long start = System.nanoTime();
            Flux.range(0, total)
                    .flatMap(i -> Mono.from(bus.emit(event(i))).onErrorResume(e -> {
                        failed.incrementAndGet();
                        return Mono.empty();
                    }), 2_000)
                    .blockLast(Duration.ofMinutes(3));
            double ceiling = total / ((System.nanoTime() - start) / 1e9);
            System.out.printf("[REAL BROKER] ceiling (2000 in flight): %d events, %d failed, %.0f confirmed/s, "
                    + "channels on the connection=%d%n", total, failed.get(), ceiling,
                    RabbitScenarioSupport.settledChannels(RABBIT, "perf-app-"));

            for (Stats stats : new Stats[]{plain[0], mixed[0], mixed[1]}) {
                assertThat(stats.failed()).as(stats.name() + " failed").isZero();
                assertThat(stats.confirmed()).as(stats.name() + " confirmed").isEqualTo(stats.sent());
            }
            assertThat(plainArrived).as("events that reached the broker").isEqualTo(plain[0].sent());
            assertThat(mixedEvents).as("events that reached the broker with discards").isEqualTo(mixed[0].sent());
            assertThat(mixedDiscards).as("discards that reached the broker").isEqualTo(mixed[1].sent());
            assertThat(plain[0].confirmedPerSecond()).as("sustained rate").isGreaterThan(TARGET_RATE * 0.95);
            assertThat(mixed[0].confirmedPerSecond()).as("sustained rate with discards")
                    .isGreaterThan(TARGET_RATE * 0.95);
            assertThat(failed.get()).as("failures at the ceiling").isZero();
            assertThat(ceiling).as("ceiling of the sender").isGreaterThan(TARGET_RATE);
        }
    }

    /**
     * Generates the load in 1 ms ticks. The latency of each message is measured from the instant it was due.
     */
    private static Stats[] drive(DomainEventBus bus, DiscardNotifier discards, int seconds, int eventsPerSecond,
                                 int discardsPerSecond) {
        Collector events = new Collector("events", seconds * eventsPerSecond);
        Collector discarded = new Collector("discards", seconds * discardsPerSecond);
        long start = System.nanoTime();
        int nextEvent = 0;
        int nextDiscard = 0;
        for (int tick = 0; tick < seconds * 1_000; tick++) {
            long due = start + tick * 1_000_000L;
            long wait;
            while ((wait = due - System.nanoTime()) > 0) {
                LockSupport.parkNanos(Math.min(wait, 200_000));
            }
            // messages due up to the end of this tick (cumulative, so rates that are not multiples of 1000 are exact)
            int eventsDue = (int) ((tick + 1L) * eventsPerSecond / 1_000);
            int discardsDue = (int) ((tick + 1L) * discardsPerSecond / 1_000);
            while (nextEvent < eventsDue) {
                int index = nextEvent++;
                Mono.from(bus.emit(event(index))).subscribe(v -> {
                }, events::fail, () -> events.confirm(index, due));
            }
            while (nextDiscard < discardsDue) {
                int index = nextDiscard++;
                discards.notifyDiscard(discardedEvent()).subscribe(v -> {
                }, discarded::fail, () -> discarded.confirm(index, due));
            }
        }
        try {
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                    .until(() -> events.pending() <= 0 && discarded.pending() <= 0);
        } catch (ConditionTimeoutException e) {
            // the stats report the messages still pending
        }
        return new Stats[]{events.stats(start), discarded.stats(start)};
    }

    private static final class Collector {
        private final String name;
        private final AtomicLongArray latenciesMicros;
        private final AtomicInteger confirmed = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final Map<String, AtomicInteger> failures = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile long lastConfirmation;

        Collector(String name, int total) {
            this.name = name;
            this.latenciesMicros = new AtomicLongArray(total);
            for (int i = 0; i < total; i++) {
                latenciesMicros.set(i, -1);
            }
        }

        void confirm(int index, long due) {
            long now = System.nanoTime();
            latenciesMicros.set(index, (now - due) / 1_000);
            lastConfirmation = now;
            confirmed.incrementAndGet();
        }

        void fail(Throwable error) {
            failures.computeIfAbsent(error.getClass().getSimpleName() + ": " + error.getMessage(),
                    key -> new AtomicInteger()).incrementAndGet();
            failed.incrementAndGet();
        }

        int pending() {
            return latenciesMicros.length() - confirmed.get() - failed.get();
        }

        Stats stats(long start) {
            long[] sorted = new long[confirmed.get()];
            int n = 0;
            for (int i = 0; i < latenciesMicros.length() && n < sorted.length; i++) {
                long value = latenciesMicros.get(i);
                if (value >= 0) {
                    sorted[n++] = value;
                }
            }
            sorted = Arrays.copyOf(sorted, n);
            Arrays.sort(sorted);
            double seconds = Math.max(1e-9, (lastConfirmation - start) / 1e9);
            if (!failures.isEmpty() || pending() > 0) {
                System.out.println("[REAL BROKER]   " + name + " failures: " + failures + ", still pending: "
                        + pending());
            }
            return new Stats(name, latenciesMicros.length(), confirmed.get(), failed.get() + pending(),
                    confirmed.get() / seconds, percentile(sorted, 0.50), percentile(sorted, 0.95),
                    percentile(sorted, 0.99), n == 0 ? 0 : sorted[n - 1] / 1_000);
        }

        private static long percentile(long[] sorted, double p) {
            return sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, Math.floor(p * sorted.length))]
                    / 1_000;
        }
    }

    private static DomainEvent<Map<String, Object>> event(int index) {
        return new DomainEvent<>(EVENT_NAME, UUID.randomUUID().toString(),
                Map.of("id", index, "customer", "customer-" + index % 1000, "amount", 1234.56, "note", NOTE));
    }

    private static RabbitMessage discardedEvent() {
        RabbitMessage.RabbitMessageProperties properties = new RabbitMessage.RabbitMessageProperties();
        properties.setContentType("application/json");
        byte[] body = "{\"name\":\"order.created\",\"eventId\":\"1\",\"data\":{\"id\":1}}"
                .getBytes(StandardCharsets.UTF_8);
        return new RabbitMessage(body, properties, "");
    }

    private static String bind(Channel admin, String queue, String routingKey) throws Exception {
        admin.queueDeclare(queue, true, false, false, null);
        admin.queueBind(queue, EVENTS_EXCHANGE, routingKey);
        return queue;
    }

    /**
     * The messages in the queue once they stop arriving (or all the expected ones are there).
     */
    private static int settledCount(Channel admin, String queue, int expected) throws Exception {
        try {
            return await().atMost(Duration.ofSeconds(15))
                    .until(() -> admin.queueDeclarePassive(queue).getMessageCount(), count -> count >= expected);
        } catch (ConditionTimeoutException e) {
            return admin.queueDeclarePassive(queue).getMessageCount();
        }
    }

    private static Connection adminConnection() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        return factory.newConnection("tc-admin");
    }
}
