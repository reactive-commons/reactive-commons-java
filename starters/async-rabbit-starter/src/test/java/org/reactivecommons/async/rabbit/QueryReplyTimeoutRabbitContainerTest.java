package org.reactivecommons.async.rabbit;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenQueries;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;

/**
 * The reply timeout of the queries on a REAL RabbitMQ broker: with {@code discardTimeoutQueries: true}, a query whose
 * handler answers well within the reply timeout of its caller must be answered, never discarded as timed out; and a
 * query still waiting in its queue once its caller stopped waiting must never be handled.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class QueryReplyTimeoutRabbitContainerTest {

    private static final String TARGET = "qto-app";
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration HANDLER_DURATION = Duration.ofMillis(300);
    private static final int QUERIES = 20;
    /**
     * Not a divisor of a second, so the queries are published at every fraction of the second.
     */
    private static final long PAUSE_MILLIS = 137;

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private static final AtomicInteger executions = new AtomicInteger();

    @Configuration
    static class CountingQueryHandlers {
        @Bean
        BrokerConfig brokerConfig() {
            return new BrokerConfig(false, true, true, REPLY_TIMEOUT);
        }

        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .serveQuery("scenario.query", query -> Mono.fromCallable(() -> {
                        executions.incrementAndGet();
                        return "ok";
                    }), String.class);
        }
    }

    @Configuration
    static class SlowQueryHandlers {
        @Bean
        BrokerConfig brokerConfig() {
            return new BrokerConfig(false, true, true, REPLY_TIMEOUT);
        }

        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .serveQuery("scenario.query", query -> Mono.delay(HANDLER_DURATION).thenReturn("ok"),
                            String.class);
        }
    }

    @Test
    void aQueryAnsweredWithinItsReplyTimeoutIsNotDiscarded() throws Exception {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "qto",
                false, true, Map.of("app.async.app.direct.discardTimeoutQueries", "true"),
                SlowQueryHandlers.class, SendDirect.class, ListenQueries.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, TARGET + ".query");
            DirectAsyncGateway gateway = context.getBean(DirectAsyncGateway.class);

            // one after another, each one PAUSE_MILLIS after the reply of the previous one
            long answered = Flux.range(0, QUERIES)
                    .concatMap(i -> gateway.requestReply(new AsyncQuery<>("scenario.query", "q" + i), TARGET,
                                    String.class)
                            .timeout(Duration.ofSeconds(5))
                            .onErrorResume(error -> Mono.empty())
                            .defaultIfEmpty("no reply")
                            .delayElement(Duration.ofMillis(PAUSE_MILLIS)))
                    .filter("ok"::equals)
                    .count()
                    .block(Duration.ofSeconds(QUERIES * 6L));

            System.out.printf("[REAL BROKER] queries of %d ms with a reply timeout of %d ms: answered=%d/%d%n",
                    HANDLER_DURATION.toMillis(), REPLY_TIMEOUT.toMillis(), answered, QUERIES);
            assertThat(answered).as("queries answered within their reply timeout").isEqualTo(QUERIES);
        }
    }

    /**
     * A query waits in the queue of a listener that is not consuming (e.g. down): once its caller stopped waiting for
     * the reply, the broker drops it, so the listener does not run it when it is back.
     */
    @Test
    void aQueryWhoseCallerStoppedWaitingExpiresInItsQueue() throws Exception {
        assertExpiredQueryIsNeverHandled("qexp", Map.of());
    }

    /**
     * With the DLQ retries the queue of the queries dead-letters the expired ones to the retry queue instead of
     * dropping them: the broker drops them when they come back, as a dead-letter cycle without any rejection, so the
     * listener never runs them either.
     */
    @Test
    void anExpiredQueryBackFromTheRetryQueueIsNotHandled() throws Exception {
        assertExpiredQueryIsNeverHandled("qdlq", Map.of("app.async.app.withDLQRetry", "true"));
    }

    private static void assertExpiredQueryIsNeverHandled(String prefix, Map<String, Object> listenerProperties)
            throws Exception {
        executions.set(0);
        String target = prefix + "-app";
        String queue = target + ".query";
        // the listener declares its queue, which stays without consumer once the listener stops
        try (AnnotationConfigApplicationContext listener = startListener(prefix, listenerProperties)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, queue);
        }
        assertThat(messages(queue)).as("the query is routed to an existing queue").isZero();
        try (AnnotationConfigApplicationContext caller = RabbitScenarioSupport.startWithHandlers(RABBIT,
                prefix + "-caller", false, true, CountingQueryHandlers.class, SendDirect.class)) {
            Throwable timeout = catchThrowable(() -> caller.getBean(DirectAsyncGateway.class)
                    .requestReply(new AsyncQuery<>("scenario.query", "late"), target, String.class)
                    .block(Duration.ofSeconds(10)));
            assertThat(timeout).as("its caller stopped waiting for the reply").isNotNull();
        }

        try (AnnotationConfigApplicationContext listener = startListener(prefix, listenerProperties)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, queue);
            boolean settled = eventually(() -> messages(queue) == 0 && messages(queue + ".DLQ") <= 0);
            // nothing is handled late
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> executions.get() == 0);
            System.out.printf("[REAL BROKER] expired query (%s): left in the queue=%d, executions=%d%n",
                    prefix, messages(queue), executions.get());

            assertThat(settled).as("not left in the queues").isTrue();
            assertThat(executions.get()).as("never handled").isZero();
        }
    }

    private static AnnotationConfigApplicationContext startListener(String prefix, Map<String, Object> properties) {
        return RabbitScenarioSupport.startWithHandlers(RABBIT, prefix, false, false, properties,
                CountingQueryHandlers.class, ListenQueries.class);
    }

    /**
     * Messages of a queue, ready and unacknowledged; -1 if it does not exist.
     */
    private static int messages(String queue) {
        try {
            String listing = RABBIT.execInContainer("rabbitmqctl", "-q", "list_queues", "name", "messages")
                    .getStdout();
            return listing.lines().map(line -> line.trim().split("\\s+"))
                    .filter(columns -> columns.length == 2 && columns[0].equals(queue))
                    .mapToInt(columns -> Integer.parseInt(columns[1])).findFirst().orElse(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Whether the condition holds within 20 seconds.
     */
    private static boolean eventually(BooleanSupplier condition) {
        try {
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until(condition::getAsBoolean);
            return true;
        } catch (ConditionTimeoutException e) {
            return false;
        }
    }
}
