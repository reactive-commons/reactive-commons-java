package org.reactivecommons.async.rabbit.listeners;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.Receiver;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenericMessageListenerRestartTest {

    /**
     * A subscription that fails synchronously (the connection is closed) used to restart the listener from within the
     * termination callback, recursing until a StackOverflowError. The restart must be scheduled instead.
     */
    @Test
    void restartsWithoutRecursingWhenTheSubscriptionFailsSynchronously() throws Exception {
        AtomicInteger subscriptions = new AtomicInteger();
        Receiver receiver = mock(Receiver.class);
        ReactiveMessageListener messageListener = mock(ReactiveMessageListener.class);
        when(messageListener.receiver()).thenReturn(receiver);
        when(messageListener.maxConcurrency()).thenReturn(1);
        when(messageListener.prefetchCount()).thenReturn(10);
        when(receiver.consumeManualAck(anyString(), any(ConsumeOptions.class)))
                .thenAnswer(invocation -> Flux.defer(() -> {
                    subscriptions.incrementAndGet();
                    return Flux.error(new IllegalStateException("connection is closed"));
                }));
        GenericMessageListener listener = new TestListener(messageListener);

        listener.startListener(); // returns instead of overflowing the stack

        awaitSubscriptions(subscriptions, 3);

        listener.stop();
        Thread.sleep(300); // let an already scheduled attempt run
        int attemptsAfterStop = subscriptions.get();
        Thread.sleep(500);
        assertEquals(attemptsAfterStop, subscriptions.get(), "the listener restarted after being stopped");
    }

    private static void awaitSubscriptions(AtomicInteger subscriptions, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (subscriptions.get() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("The listener did not re-subscribe, attempts: " + subscriptions.get());
            }
            Thread.sleep(50);
        }
    }

    private static class TestListener extends GenericMessageListener {

        TestListener(ReactiveMessageListener listener) {
            super("test.queue", listener, false, false, -1, 1000, mock(DiscardNotifier.class), "test",
                    mock(CustomReporter.class));
        }

        @Override
        protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
            return message -> Mono.empty();
        }

        @Override
        protected String getExecutorPath(AcknowledgableDelivery msj) {
            return "path";
        }

        @Override
        protected Object parseMessageForReporter(Message msj) {
            return msj;
        }

        @Override
        protected String getKind() {
            return "test";
        }
    }
}
