package org.reactivecommons.async.rabbit;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.HandlerRegistry;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenQueries;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;

/**
 * The replies of the queries on a REAL RabbitMQ broker. Every reply of an application arrives on the same reply
 * queue, whose deliveries the RabbitMQ client dispatches one after another on a single thread: the conversion of a
 * reply and the code of its caller must not run on that thread, otherwise
 * <ul>
 *     <li>a caller that takes long to process its reply (or blocks) holds back the replies of every other caller;</li>
 *     <li>the replies are converted one after another, however many processors there are.</li>
 * </ul>
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReplyDispatchRabbitContainerTest {

    private static final String PREFIX = "rdp";
    private static final String TARGET = PREFIX + "-app";
    private static final Duration SLOW_REPLY_DELAY = Duration.ofMillis(500);
    private static final Duration CALLER_PROCESSING = Duration.ofSeconds(5);
    private static final Duration CONVERSION_TIME = Duration.ofMillis(200);
    private static final int PARALLEL_QUERIES = 8;

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private static final AtomicInteger CONVERTING = new AtomicInteger();
    private static final AtomicInteger MOST_CONVERTING = new AtomicInteger();

    @Configuration
    static class QueryHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .serveQuery("reply.fast", query -> Mono.just("fast"), String.class)
                    .serveQuery("reply.slow", query -> Mono.delay(SLOW_REPLY_DELAY).thenReturn("slow"),
                            String.class)
                    .serveQuery("reply.large", query -> Mono.just(new SlowToConvert(query)), String.class);
        }
    }

    /**
     * A reply whose conversion takes {@link #CONVERSION_TIME}, as the conversion of a large reply does.
     */
    @Getter
    @NoArgsConstructor
    public static class SlowToConvert {
        private String value;

        SlowToConvert(String value) {
            this.value = value;
        }

        public void setValue(String value) {
            MOST_CONVERTING.accumulateAndGet(CONVERTING.incrementAndGet(), Math::max);
            try {
                // simulates the work of a slow conversion on the converting thread: it waits for nothing
                Thread.sleep(CONVERSION_TIME.toMillis()); // NOSONAR
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                CONVERTING.decrementAndGet();
            }
            this.value = value;
        }
    }

    @Test
    void aCallerProcessingItsReplyDoesNotHoldBackTheRepliesOfTheOtherCallers() {
        try (AnnotationConfigApplicationContext context = start()) {
            DirectAsyncGateway gateway = context.getBean(DirectAsyncGateway.class);
            CountDownLatch otherReplyReceived = new CountDownLatch(1);
            List<String> callerThreads = new CopyOnWriteArrayList<>();
            AtomicLong otherReplyMillis = new AtomicLong(-1);
            long start = System.nanoTime();

            // its reply arrives first, and it waits (as a slow or blocking caller does) for the other caller to get
            // its own reply
            Mono<Boolean> waitingCaller = gateway.requestReply(new AsyncQuery<>("reply.fast", "a"), TARGET,
                            String.class)
                    .map(reply -> {
                        callerThreads.add(Thread.currentThread().getName());
                        return awaitQuietly(otherReplyReceived);
                    });
            Mono<String> otherCaller = gateway.requestReply(new AsyncQuery<>("reply.slow", "b"), TARGET,
                            String.class)
                    .doOnNext(reply -> {
                        otherReplyMillis.set(millisSince(start));
                        otherReplyReceived.countDown();
                    });
            Boolean otherReplyArrivedMeanwhile = Mono.zip(waitingCaller, otherCaller).map(Tuple2::getT1)
                    .block(Duration.ofSeconds(30));

            System.out.printf("[REAL BROKER] caller processing its reply on %s; the other reply (sent after %d ms) "
                            + "reached its caller after %d ms, while the first one was processing: %s%n", callerThreads,
                    SLOW_REPLY_DELAY.toMillis(), otherReplyMillis.get(), otherReplyArrivedMeanwhile);
            assertThat(otherReplyArrivedMeanwhile)
                    .as("the other caller got its reply while the first one was processing its own").isTrue();
            assertThat(otherReplyMillis.get()).as("time for the other caller to get its reply")
                    .isLessThan(CALLER_PROCESSING.toMillis());
        }
    }

    @Test
    void theRepliesAreConvertedInParallel() {
        try (AnnotationConfigApplicationContext context = start()) {
            DirectAsyncGateway gateway = context.getBean(DirectAsyncGateway.class);
            MOST_CONVERTING.set(0);
            long start = System.nanoTime();

            Long replies = Flux.range(0, PARALLEL_QUERIES)
                    .flatMap(i -> gateway.requestReply(new AsyncQuery<>("reply.large", "q" + i), TARGET,
                            SlowToConvert.class))
                    .count()
                    .block(Duration.ofSeconds(60));

            System.out.printf("[REAL BROKER] %d replies of %d ms to convert: received in %d ms, at most %d "
                            + "converted at the same time%n", PARALLEL_QUERIES, CONVERSION_TIME.toMillis(),
                    millisSince(start), MOST_CONVERTING.get());
            assertThat(replies).as("replies received").isEqualTo(PARALLEL_QUERIES);
            assertThat(MOST_CONVERTING.get()).as("replies converted at the same time").isGreaterThan(1);
        }
    }

    /**
     * An application that serves the queries and calls itself, once it answers them: the reply listener starts with
     * the gateway, the query listener once the context is refreshed.
     */
    private static AnnotationConfigApplicationContext start() {
        AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, PREFIX, false,
                true, QueryHandlers.class, SendDirect.class, ListenQueries.class);
        RabbitScenarioSupport.awaitConsumers(RABBIT, TARGET + ".query");
        context.getBean(DirectAsyncGateway.class)
                .requestReply(new AsyncQuery<>("reply.fast", "warm-up"), TARGET, String.class)
                .block(Duration.ofSeconds(30));
        return context;
    }

    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(CALLER_PROCESSING.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long millisSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
