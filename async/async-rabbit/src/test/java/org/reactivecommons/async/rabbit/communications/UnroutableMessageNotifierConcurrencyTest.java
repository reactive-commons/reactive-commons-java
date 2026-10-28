package org.reactivecommons.async.rabbit.communications;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class UnroutableMessageNotifierConcurrencyTest {

    private static final int THREADS = 4;
    private static final int MESSAGES_PER_THREAD = 50; // within the buffer of the sink: only concurrency may fail

    /**
     * The default notifier is shared by every domain: the returns of several connections reach it at the same time,
     * and none of them may be lost.
     */
    @Test
    void returnsNotifiedConcurrentlyAreAllDelivered() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UnroutableMessageNotifier notifier = new UnroutableMessageNotifier(registry, null);
        AtomicInteger processed = new AtomicInteger();
        notifier.listenToUnroutableMessages(result -> Mono.fromRunnable(processed::incrementAndGet));
        OutboundMessageResult<OutboundMessage> returned =
                new OutboundMessageResult<>(new OutboundMessage("exchange", "rkey", new byte[0]), true, true);

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < THREADS; t++) {
                executor.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < MESSAGES_PER_THREAD; i++) {
                        notifier.notifyUnroutableMessage(returned);
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(registry.find("reactive_commons.publish.unroutable.emit.failed").tag("domain", "").counter()
                .count()).as("no notification rejected for being concurrent").isZero();
        assertThat(registry.find("reactive_commons.publish.unroutable.messages").tag("domain", "").counter()
                .count()).isEqualTo(THREADS * MESSAGES_PER_THREAD);
        await().atMost(Duration.ofSeconds(5)).until(() -> processed.get() >= THREADS * MESSAGES_PER_THREAD);
        assertThat(processed.get()).as("every notification reached the handler")
                .isEqualTo(THREADS * MESSAGES_PER_THREAD);
    }
}
