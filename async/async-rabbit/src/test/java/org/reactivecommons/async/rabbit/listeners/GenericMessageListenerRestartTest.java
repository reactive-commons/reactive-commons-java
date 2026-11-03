package org.reactivecommons.async.rabbit.listeners;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.RetryPolicy;
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

import static org.awaitility.Awaitility.await;
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
    void restartsWithoutRecursingWhenTheSubscriptionFailsSynchronously() {
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
        // an attempt already scheduled may still run: then the listener stops re-subscribing
        AtomicInteger seen = new AtomicInteger(-1);
        await().alias("the listener stopped re-subscribing").during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(3)).until(() -> {
                    int attempts = subscriptions.get();
                    return attempts == seen.getAndSet(attempts);
                });
    }

    private static void awaitSubscriptions(AtomicInteger subscriptions, int expected) {
        await().alias("the listener re-subscribed").atMost(Duration.ofSeconds(5))
                .until(() -> subscriptions.get() >= expected);
    }

    private static class TestListener extends GenericMessageListener {

        TestListener(ReactiveMessageListener listener) {
            super(new ListenerContext<>(listener, null, null, new RetryPolicy(false, -1, 1000), false,
                    mock(DiscardNotifier.class), mock(CustomReporter.class)), "test.queue", "test");
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
