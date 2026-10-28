package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.exceptions.SendConfirmUnknownException;
import org.reactivecommons.async.commons.exceptions.SendFailureBrokerBlockedException;
import org.reactivecommons.async.rabbit.communications.ConnectionBlockedState;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Raises a REAL memory alarm on the broker ({@code rabbitmqctl set_vm_memory_high_watermark}), so the broker itself
 * sends {@code connection.blocked} / {@code connection.unblocked} and the client's {@code BlockedListener} drives the
 * {@link ConnectionBlockedState}. Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConnectionBlockedRealAlarmRabbitContainerTest {

    private static final Duration HEALTH_GRACE = Duration.ofSeconds(1);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(3);
    private static volatile CompletableFuture<Void> releaseHandlers = new CompletableFuture<>();

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @AfterEach
    void clearAlarm() throws Exception {
        rabbitmqctl("set_vm_memory_high_watermark", "0.4");
    }

    @Test
    void aRealMemoryAlarmBlocksTheSenderAndClearsWhenTheAlarmClears() throws Exception {
        try (AnnotationConfigApplicationContext context = start("real", TestProps.class)) {
            RabbitMQBrokerProvider provider = provider(context);
            DomainEventBus bus = context.getBean(DomainEventBus.class);
            ConnectionBlockedState state = provider.sender().blockedState();
            emit(bus);

            raiseMemoryAlarm();
            // connection.blocked is sent to publishing connections: publish once so the broker blocks this one
            Throwable firstError = catchThrowable(() -> emit(bus));
            awaitFor("connection.blocked from the broker", state::isBlocked);

            assertThat(state.blockedReason()).isNotBlank();
            Throwable error = catchThrowable(() -> emit(bus));
            assertThat(error).as("first publish after the alarm: %s", firstError)
                    .hasCauseInstanceOf(SendFailureBrokerBlockedException.class);
            awaitFor("health down once the grace elapses",
                    () -> provider.healthCheck().block().status() == RCHealth.Status.DOWN);

            rabbitmqctl("set_vm_memory_high_watermark", "0.4");
            awaitFor("connection.unblocked from the broker", () -> !state.isBlocked());
            emit(bus);
            assertThat(provider.healthCheck().block().status()).isEqualTo(RCHealth.Status.UP);
        }
    }

    /**
     * The broker only notifies {@code connection.blocked} once the connection publishes during the alarm: that first
     * publish cannot fail fast and ends, within the send timeout, as an unknown outcome. Every later one fails fast.
     */
    @Test
    void thePublishThatTriggersTheBlockEndsAsConfirmUnknownAndTheNextOnesFailFast() throws Exception {
        try (AnnotationConfigApplicationContext context = start("race", TestProps.class)) {
            DomainEventBus bus = context.getBean(DomainEventBus.class);
            ConnectionBlockedState state = provider(context).sender().blockedState();
            emit(bus);

            raiseMemoryAlarm();
            long t0 = System.nanoTime();
            Throwable first = catchThrowable(() -> emit(bus));
            long firstMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
            awaitFor("connection.blocked from the broker", state::isBlocked);
            long t1 = System.nanoTime();
            Throwable next = catchThrowable(() -> emit(bus));
            long nextMs = Duration.ofNanos(System.nanoTime() - t1).toMillis();

            assertThat(first).hasCauseInstanceOf(SendConfirmUnknownException.class);
            assertThat(firstMs).isLessThan(SEND_TIMEOUT.plusSeconds(2).toMillis());
            assertThat(next).hasCauseInstanceOf(SendFailureBrokerBlockedException.class);
            assertThat(nextMs).as("fail-fast duration").isLessThan(1_000);
            rabbitmqctl("set_vm_memory_high_watermark", "0.4");
        }
    }

    /**
     * The listeners share the connection of the sender: once the broker blocks it, it stops reading from the socket,
     * so the {@code basic.cancel} RPC of the listeners never gets its reply and the context close hangs.
     */
    @Test
    void shuttingDownWhileBlockedCompletes() throws Exception {
        AnnotationConfigApplicationContext context = start("close", TestProps.class);
        DomainEventBus bus = context.getBean(DomainEventBus.class);
        ConnectionBlockedState state = provider(context).sender().blockedState();
        emit(bus);
        raiseMemoryAlarm();
        catchThrowable(() -> emit(bus));
        awaitFor("connection.blocked from the broker", state::isBlocked);

        List<String> aborts = new CopyOnWriteArrayList<>();
        java.util.logging.Logger senderLog = java.util.logging.Logger.getLogger(
                org.reactivecommons.async.rabbit.communications.ReactiveMessageSender.class.getName());
        java.util.logging.Handler abortWarnings = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord logRecord) {
                if (logRecord.getMessage() != null && logRecord.getMessage().contains("aborting the connection")) {
                    aborts.add(logRecord.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        senderLog.addHandler(abortWarnings);
        long t0 = System.nanoTime();
        Thread closer = new Thread(context::close, "context-closer");
        closer.start();
        closer.join(Duration.ofSeconds(30).toMillis());
        boolean hung = closer.isAlive();
        long closeMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        rabbitmqctl("set_vm_memory_high_watermark", "0.4"); // releases the hung close, if any
        closer.join(Duration.ofSeconds(30).toMillis());
        senderLog.removeHandler(abortWarnings);
        System.out.println("[CLOSE] context closed while blocked in " + closeMs + " ms, abort warnings: "
                + aborts.size());

        assertThat(hung).as("context close still running 30 s after it started while blocked").isFalse();
        assertThat(aborts).as("the blocked connection is aborted once, not once per flow").hasSize(1);
    }

    /**
     * The connection is closed while blocked (broker restart, network partition, operator, ...) and recovered after
     * the alarm cleared: the new connection never receives {@code connection.unblocked}, because it was never blocked.
     */
    @Test
    void theBlockedStateIsClearedWhenTheConnectionIsRecovered() throws Exception {
        try (AnnotationConfigApplicationContext context = start("recover", TestProps.class)) {
            RabbitMQBrokerProvider provider = provider(context);
            DomainEventBus bus = context.getBean(DomainEventBus.class);
            ConnectionBlockedState state = provider.sender().blockedState();
            emit(bus);

            raiseMemoryAlarm();
            catchThrowable(() -> emit(bus));
            awaitFor("connection.blocked from the broker", state::isBlocked);

            // close the blocked connection and clear the alarm before the client recovers it (5 s by default)
            ExecResult result = RABBIT.execInContainer("sh", "-c",
                    "rabbitmqctl close_all_connections 'test' && rabbitmqctl set_vm_memory_high_watermark 0.4");
            assertThat(result.getExitCode()).as(result.getStderr()).isZero();

            awaitFor("the blocked state is cleared after the recovery", () -> !state.isBlocked());
            awaitFor("publishing works again", () -> catchThrowable(() -> emit(bus)) == null);
            // the flows re-subscribe after the recovery: the health may be DOWN briefly, but must converge to UP
            awaitFor("the health is UP again", () -> provider.healthCheck().block().status() == RCHealth.Status.UP);
        }
    }

    /**
     * The broker stops reading from a publishing connection during an alarm: the listeners must not share it, or
     * their acknowledgements stay unread and the queues cannot drain (which is what frees the broker memory).
     */
    @Test
    void listenersKeepAcknowledgingWhileThePublisherIsBlocked() throws Exception {
        releaseHandlers = new CompletableFuture<>();
        try (AnnotationConfigApplicationContext context = startWithHandlers("acks", HeldHandlers.class,
                TestProps.class)) {
            DomainEventBus bus = context.getBean(DomainEventBus.class);
            ConnectionBlockedState state = provider(context).sender().blockedState();
            for (int i = 0; i < 5; i++) {
                emit(bus); // delivered, and held unacknowledged by the handlers
            }
            awaitFor("5 deliveries held by the handlers", () -> unacknowledged("acks-app.subsEvents") == 5);

            raiseMemoryAlarm();
            catchThrowable(() -> emit(bus));
            awaitFor("connection.blocked from the broker", state::isBlocked);
            // "blocking" = would be paused if it published; only "blocked" connections are paused
            Map<String, String> states = connectionStates("acks-app-");
            assertThat(states).containsEntry("sender", "blocked").containsKey("listener");
            assertThat(states.get("listener")).isNotEqualTo("blocked");

            releaseHandlers.complete(null); // the handlers acknowledge while the alarm is still raised
            awaitFor("the acknowledgements reach the broker", () -> unacknowledged("acks-app.subsEvents") == 0);
            rabbitmqctl("set_vm_memory_high_watermark", "0.4");
        }
    }

    /**
     * A sustained flood while blocked fails fast: no message waits for the send timeout, none is buffered in memory,
     * and none of them reaches the broker once the alarm clears.
     */
    @Test
    void aFloodWhileBlockedFailsFastAndNothingIsPublishedLate() throws Exception {
        try (AnnotationConfigApplicationContext context = start("flood", TestProps.class)) {
            RabbitMQBrokerProvider provider = provider(context);
            DomainEventBus bus = context.getBean(DomainEventBus.class);
            ConnectionBlockedState state = provider.sender().blockedState();
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            emit(bus);

            raiseMemoryAlarm();
            catchThrowable(() -> emit(bus));
            awaitFor("connection.blocked from the broker", state::isBlocked);

            double acksBefore = acks(registry);
            String blob = "x".repeat(64 * 1024);
            int messages = 1500;
            long t0 = System.nanoTime();
            List<reactor.util.function.Tuple2<Long, String>> outcomes = Flux.range(0, messages)
                    .flatMap(i -> Mono.from(bus.emit(new DomainEvent<>("scenario.event",
                                    UUID.randomUUID().toString(), Map.of("blob", blob))))
                            .thenReturn("ok")
                            // the event bus wraps the failure of the sender
                            .onErrorResume(e -> Mono.just((e.getCause() != null ? e.getCause() : e).getClass()
                                    .getSimpleName()))
                            .elapsed(), messages)
                    .collectList()
                    .block(Duration.ofSeconds(60));
            long floodMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();

            Map<String, Long> byOutcome = outcomes.stream()
                    .collect(Collectors.groupingBy(reactor.util.function.Tuple2::getT2, TreeMap::new,
                            Collectors.counting()));
            long slowest = outcomes.stream().mapToLong(reactor.util.function.Tuple2::getT1).max().orElse(0);

            rabbitmqctl("set_vm_memory_high_watermark", "0.4");
            awaitFor("publishing works again", () -> catchThrowable(() -> emit(bus)) == null);
            // anything buffered while blocked would reach the broker now
            await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(7))
                    .until(() -> acks(registry) - acksBefore <= 2);
            // besides the flood: the emit that triggered the block (unknown outcome) and the one that succeeded
            double lateAcks = acks(registry) - acksBefore;
            System.out.println("[FLOOD] " + messages + " sends in " + floodMs + " ms, slowest " + slowest
                    + " ms, outcomes " + byOutcome + ", confirmed by the broker afterwards " + lateAcks);

            assertThat(byOutcome).containsOnlyKeys("SendFailureBrokerBlockedException");
            assertThat(slowest).as("fail-fast duration").isLessThan(1_000);
            assertThat(lateAcks).as("none of the flooded messages is published once the alarm clears")
                    .isLessThanOrEqualTo(2);
        }
    }

    private static AnnotationConfigApplicationContext start(String prefix, Class<?>... features) throws Exception {
        return startWithHandlers(prefix, RabbitScenarioSupport.Handlers.class, features);
    }

    private static AnnotationConfigApplicationContext startWithHandlers(String prefix, Class<?> handlers,
                                                                        Class<?>... features) {
        Class<?>[] all = new Class<?>[features.length + 2];
        all[0] = SendEvents.class;
        all[1] = ListenEvents.class;
        System.arraycopy(features, 0, all, 2, features.length);
        AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, prefix,
                false, true, handlers, all);
        RabbitScenarioSupport.awaitConsumers(RABBIT, prefix + "-app.subsEvents");
        return context;
    }

    private static RabbitMQBrokerProvider provider(AnnotationConfigApplicationContext context) {
        return (RabbitMQBrokerProvider) context.getBean(ConnectionManager.class)
                .getProviders().get(RabbitScenarioSupport.DEFAULT_DOMAIN);
    }

    private static void emit(DomainEventBus bus) {
        Mono.from(bus.emit(new DomainEvent<>("scenario.event", UUID.randomUUID().toString(), Map.of("i", 0))))
                .block(Duration.ofSeconds(15));
    }

    private static double acks(MeterRegistry registry) {
        var counter = registry.find("reactive_commons.publish.outcome").tag("outcome", "ack")
                .tag("confirm", "true").counter();
        return counter == null ? 0 : counter.count();
    }

    private static int unacknowledged(String queue) {
        try {
            String listing = RABBIT.execInContainer("rabbitmqctl", "-q", "list_queues", "name",
                    "messages_unacknowledged").getStdout();
            return listing.lines().map(line -> line.trim().split("\\s+"))
                    .filter(columns -> columns.length == 2 && columns[0].equals(queue))
                    .mapToInt(columns -> Integer.parseInt(columns[1]))
                    .findFirst().orElse(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * State of each connection of a domain, by role ({@code sender} / {@code listener}, the suffix of its name).
     */
    private static Map<String, String> connectionStates(String connectionNamePrefix) throws Exception {
        String listing = RABBIT.execInContainer("rabbitmqctl", "-q", "list_connections", "client_properties",
                "state").getStdout();
        Map<String, String> states = new java.util.HashMap<>();
        for (String line : listing.split("\n")) {
            if (line.contains("\"" + connectionNamePrefix)) {
                String role = line.contains("-sender\"") ? "sender" : line.contains("-listener\"") ? "listener" : "?";
                String[] columns = line.trim().split("\t");
                states.put(role, columns[columns.length - 1].trim());
            }
        }
        return states;
    }

    private static void raiseMemoryAlarm() throws Exception {
        rabbitmqctl("set_vm_memory_high_watermark", "0.0000001");
    }

    private static void rabbitmqctl(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "rabbitmqctl";
        System.arraycopy(args, 0, command, 1, args.length);
        ExecResult result = RABBIT.execInContainer(command);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private static void awaitFor(String description, BooleanSupplier condition) {
        await().alias(description).atMost(Duration.ofSeconds(30)).until(condition::getAsBoolean);
    }

    @Configuration
    static class HeldHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenEvent("scenario.event", event -> Mono.fromFuture(releaseHandlers), Map.class);
        }
    }

    @Configuration
    static class TestProps {
        @Bean
        AsyncPropsDomain.RabbitPropsCustomizer testPropsCustomizer() {
            return domainProperties -> domainProperties.customize(RabbitScenarioSupport.DEFAULT_DOMAIN, props -> {
                props.setBlockedHealthGrace(HEALTH_GRACE);
                props.setSendTimeout(SEND_TIMEOUT);
            });
        }
    }
}
