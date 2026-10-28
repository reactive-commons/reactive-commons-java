package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Connection;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.rabbit.communications.PublisherStatus;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.rabbit.health.RabbitReactiveHealthIndicator;
import org.reactivecommons.async.commons.exceptions.ReplyLostOnReconnectException;
import org.reactivecommons.async.rabbit.listeners.SubscriberStatus;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenQueries;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Subscriber-side resilience against a REAL broker: what a caller sees when a reply is lost, what happens to a hung
 * handler, and how the health check reports a listener that recovered or keeps failing.
 */
@Testcontainers(disabledWithoutDocker = true)
class SubscriberResilienceRabbitContainerTest {

    /**
     * Longer than closing a connection with rabbitmqctl takes even under load, so the reply is always published after
     * the connection is lost; shorter than the reply timeout (15 s).
     */
    private static final Duration SLOW_REPLY = Duration.ofSeconds(8);
    private static final AtomicInteger handlersStarted = new AtomicInteger();
    private static final AtomicInteger slowQueriesStarted = new AtomicInteger();
    private static final AtomicInteger handlersInterrupted = new AtomicInteger();

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    /**
     * The requester's consuming connection drops while a query is in flight: the reply is published while its
     * exclusive reply queue does not exist, so it is lost. The caller is told so right away, also on a second loss
     * after the connection was recovered, and queries work again once it is.
     */
    @Test
    void aReplyLostWhileTheReplyConnectionRecoversFailsTheCallerPromptly() throws Exception {
        try (AnnotationConfigApplicationContext server = startServer("srv1");
             AnnotationConfigApplicationContext requester = RabbitScenarioSupport.startWithHandlers(RABBIT, "req1",
                     false, true, NoHandlers.class, SendDirect.class)) {
            DirectAsyncGateway gateway = requester.getBean(DirectAsyncGateway.class);
            for (int loss = 1; loss <= 2; loss++) {
                awaitReplyQueue("req1-app");
                int started = slowQueriesStarted.get();
                CompletableFuture<String> reply = gateway.requestReply(new AsyncQuery<>("slow.query", "q"),
                        "srv1-app", String.class).toFuture();
                // the query is being handled: its reply is in flight
                await().atMost(Duration.ofSeconds(10)).until(() -> slowQueriesStarted.get() > started);
                long t0 = System.nanoTime();
                closeConnection("req1-app-", "-listener");
                Throwable outcome = outcomeOf(reply);
                long elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
                System.out.println("[REAL BROKER] reply lost on connection loss #" + loss + ": " + outcome
                        + " " + elapsedMs + " ms after the connection was closed");

                assertThat(outcome).as("loss #%d: the caller is told the reply is lost", loss)
                        .isInstanceOf(ReplyLostOnReconnectException.class);
                assertThat(elapsedMs).as("loss #%d: told before the reply was even published", loss)
                        .isLessThan(SLOW_REPLY.toMillis());
            }
            awaitReplyQueue("req1-app");
            assertThat(gateway.requestReply(new AsyncQuery<>("slow.query", "q"), "srv1-app", String.class)
                    .block(Duration.ofSeconds(15))).as("answered once the connection is recovered").isEqualTo("ok");
        }
    }

    /**
     * The reply listener of one domain restarts (its queue was deleted): the queries in flight on ANOTHER domain,
     * whose reply queue is fine, must still get their replies.
     */
    @Test
    void aReplyListenerRestartInOneDomainDoesNotFailTheQueriesOfAnother() throws Exception {
        try (AnnotationConfigApplicationContext server = startServer("srv2");
             AnnotationConfigApplicationContext requester = RabbitScenarioSupport.startWithHandlers(RABBIT, "req2",
                     true, true, NoHandlers.class, SendDirect.class)) {
            String appReplyQueue = awaitReplyQueue("req2-app");
            awaitReplyQueue("req2-extra");
            DirectAsyncGateway gateway = requester.getBean(DirectAsyncGateway.class);

            int started = slowQueriesStarted.get();
            CompletableFuture<String> otherDomain = gateway.requestReply(new AsyncQuery<>("slow.query", "q"),
                    "srv2-app", String.class, RabbitScenarioSupport.EXTRA_DOMAIN).toFuture();
            // the query is being handled: its reply is in flight
            await().atMost(Duration.ofSeconds(10)).until(() -> slowQueriesStarted.get() > started);
            ExecResult deleted = RABBIT.execInContainer("rabbitmqctl", "delete_queue", appReplyQueue);
            assertThat(deleted.getExitCode()).as(deleted.getStderr()).isZero();
            Throwable outcome = outcomeOf(otherDomain);
            System.out.println("[REAL BROKER] other domain's query after a reply listener restart: " + outcome
                    + (outcome == null ? " (reply " + otherDomain.getNow(null) + ")" : ""));

            assertThat(outcome).as("the query of the other domain is answered").isNull();
            assertThat(otherDomain.getNow(null)).isEqualTo("ok");
        }
    }

    /**
     * A handler blocks its thread (e.g. a JDBC call without timeout). With handlerTimeout the message is released,
     * but the thread should be released too, otherwise every redelivery parks one more handler thread.
     */
    @Test
    void aTimedOutBlockingHandlerReleasesItsThread() throws Exception {
        handlersStarted.set(0);
        handlersInterrupted.set(0);
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "hto",
                false, false, BlockingHandlers.class, SendEvents.class, ListenEvents.class, HandlerTimeout.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, "hto-app.subsEvents");
            Mono.from(context.getBean(DomainEventBus.class).emit(new DomainEvent<>("blocking.event",
                    UUID.randomUUID().toString(), Map.of()))).block(Duration.ofSeconds(10));

            // handlerTimeout 1 s + retryDelay 1 s: a delivery every ~2 s, each one releasing the previous thread
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> handlersStarted.get() >= 4 && parkedHandlerThreads() <= 1);
            long parked = parkedHandlerThreads();
            System.out.println("[REAL BROKER] handler timeout: deliveries=" + handlersStarted.get() + ", interrupted="
                    + handlersInterrupted.get() + ", handler threads still parked in the handler=" + parked);

            assertThat(handlersStarted.get()).as("the message was redelivered after each timeout")
                    .isGreaterThan(1);
            assertThat(parked).as("timed-out handler threads are released").isLessThanOrEqualTo(1);
        }
    }

    /**
     * Three failed restarts, then the consumer is registered again on a queue without traffic: once the
     * registration is stable the listener is healthy, without waiting for a message that may never come.
     */
    @Test
    void aListenerRegisteredStablyAfterFailedRestartsIsReportedUp() {
        Instant now = Instant.now();
        SubscriberStatus recovered = new SubscriberStatus("idle.queue", "tag", true, now.minusSeconds(6), null, null,
                4, 3, now.minusSeconds(7), "PRECONDITION_FAILED");

        assertThat(healthOf(recovered).status()).isEqualTo(RCHealth.Status.UP);
    }

    /**
     * The consumer is registered but keeps being dropped right away: the listener is still broken.
     */
    @Test
    void aListenerThatKeepsFailingRightAfterRegisteringIsReportedDown() {
        Instant now = Instant.now();
        SubscriberStatus flapping = new SubscriberStatus("flapping.queue", "tag", true, now.minusSeconds(1), null,
                null, 4, 3, now.minusSeconds(1), "PRECONDITION_FAILED");

        RCHealth result = healthOf(flapping);
        assertThat(result.status()).isEqualTo(RCHealth.Status.DOWN);
        assertThat(result.details()).containsEntry("brokenListeners", java.util.List.of("flapping.queue"));
    }

    private static RCHealth healthOf(SubscriberStatus status) {
        Connection connection = mock(Connection.class);
        when(connection.isOpen()).thenReturn(true);
        when(connection.getServerProperties()).thenReturn(Map.of("version", "4.1.0"));
        RabbitReactiveHealthIndicator health = new RabbitReactiveHealthIndicator("app",
                () -> Optional.of(connection), () -> Optional.of(connection), PublisherStatus::healthy,
                () -> Map.of(status.queueName(), status), Duration.ofSeconds(5), Duration.ofSeconds(10));
        return health.health().block();
    }

    /**
     * Handler threads blocked in a handler of {@link BlockingHandlers}.
     */
    private static long parkedHandlerThreads() {
        return Thread.getAllStackTraces().entrySet().stream()
                .filter(e -> e.getKey().getName().startsWith("rc-handlers"))
                .filter(e -> Arrays.stream(e.getValue())
                        .anyMatch(f -> f.getClassName().equals(BlockingHandlers.class.getName())
                                || f.getMethodName().contains("lambda$handlerRegistry")))
                .count();
    }

    private static AnnotationConfigApplicationContext startServer(String prefix) {
        AnnotationConfigApplicationContext server = RabbitScenarioSupport.startWithHandlers(RABBIT, prefix, false,
                false, SlowQueryHandlers.class, ListenQueries.class);
        RabbitScenarioSupport.awaitConsumers(RABBIT, prefix + "-app.query");
        return server;
    }

    private static Throwable outcomeOf(CompletableFuture<String> reply) throws Exception {
        try {
            reply.get(30, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    /**
     * Name of the (exclusive) reply queue of an application, once it has its consumer.
     */
    private static String awaitReplyQueue(String appName) {
        // the connection recovery takes 5 s, much more on a loaded machine (e.g. the whole build in parallel)
        return await().alias("a reply queue with a consumer for " + appName).atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> replyQueueWithAConsumer(appName), Optional::isPresent)
                .orElseThrow();
    }

    private static Optional<String> replyQueueWithAConsumer(String appName) throws Exception {
        String listing = RABBIT.execInContainer("rabbitmqctl", "-q", "list_queues", "name", "consumers").getStdout();
        return listing.lines().map(line -> line.trim().split("\\s+"))
                .filter(c -> c.length == 2 && c[0].startsWith(appName) && c[0].contains("replies")
                        && !c[1].equals("0"))
                .map(c -> c[0]).findFirst();
    }

    private static void closeConnection(String namePrefix, String nameSuffix) throws Exception {
        String listing = RABBIT.execInContainer("rabbitmqctl", "-q", "list_connections", "pid", "client_properties")
                .getStdout();
        String pid = listing.lines()
                .filter(line -> line.contains("\"" + namePrefix) && line.contains(nameSuffix + "\""))
                .map(line -> line.trim().split("\t")[0])
                .findFirst().orElseThrow(() -> new IllegalStateException("connection not found: " + listing));
        ExecResult result = RABBIT.execInContainer("rabbitmqctl", "close_connection", pid, "finding test");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    @Configuration
    static class NoHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register();
        }
    }

    @Configuration
    static class SlowQueryHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .serveQuery("slow.query", query -> Mono.delay(SLOW_REPLY).thenReturn("ok")
                            .doOnSubscribe(subscription -> slowQueriesStarted.incrementAndGet()), String.class);
        }
    }

    @Configuration
    static class BlockingHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenEvent("blocking.event", event -> Mono.fromRunnable(BlockingHandlers::block), Map.class);
        }

        private static void block() {
            handlersStarted.incrementAndGet();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                handlersInterrupted.incrementAndGet();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Configuration
    static class HandlerTimeout {
        @Bean
        AsyncPropsDomain.RabbitPropsCustomizer handlerTimeoutCustomizer() {
            return domainProperties -> domainProperties.customize(RabbitScenarioSupport.DEFAULT_DOMAIN, props ->
                    props.setHandlerTimeout(Duration.ofSeconds(1)));
        }
    }
}
