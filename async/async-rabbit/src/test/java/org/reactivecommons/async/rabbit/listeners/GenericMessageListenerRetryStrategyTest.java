package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.Receiver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retry strategy applied by {@link GenericMessageListener} when a handler fails, for every combination of
 * {@code withDLQRetry} and {@code maxRetries} documented in the RabbitMQ configuration guide:
 * <ul>
 *     <li>{@code withDLQRetry=false}, {@code maxRetries=-1} (defaults): retried indefinitely, every
 *     {@code retryDelay}, by requeueing the message.</li>
 *     <li>{@code withDLQRetry=false}, {@code maxRetries=N}: retried N times in memory, then discarded.</li>
 *     <li>{@code withDLQRetry=true}: rejected to the DLQ until {@code maxRetries} (10 when -1) dead-letterings,
 *     then discarded.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GenericMessageListenerRetryStrategyTest {

    private static final long DELIVERY_TAG = 7L;
    private static final int RETRY_DELAY = 50;
    private static final String DOMAIN = "test-domain";
    private static final String QUEUE = "test-queue";

    @Mock
    private Receiver receiver;
    @Mock
    private TopologyCreator topologyCreator;
    @Mock
    private DiscardNotifier discardNotifier;
    @Mock
    private CustomReporter errorReporter;

    private final Channel channel = mock(Channel.class);
    private final AtomicInteger handlerCalls = new AtomicInteger();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void defaultConfigurationRetriesTheMessageInsteadOfDiscardingIt() throws Exception {
        start(false, -1, delivery(null));

        // retried: requeued after retryDelay, never discarded
        verify(channel, timeout(2_000)).basicNack(DELIVERY_TAG, false, true);
        verify(discardNotifier, never()).tryNotifyDiscard(any(Message.class));
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertThat(handlerCalls.get()).isEqualTo(1);
    }

    @Test
    void localRetriesAreExhaustedAndThenTheMessageIsDiscarded() throws Exception {
        start(false, 2, delivery(null));

        verify(channel, timeout(2_000)).basicAck(DELIVERY_TAG, false);
        verify(discardNotifier).tryNotifyDiscard(any(Message.class));
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        assertThat(handlerCalls.get()).as("first attempt + 2 local retries").isEqualTo(3);
        assertThat(counter("reactive_commons.consumer.retry.attempts")).isEqualTo(2.0);
        assertThat(counter("reactive_commons.consumer.retry.exhausted")).isEqualTo(1.0);
    }

    @Test
    void dlqRetryRejectsTheMessageToTheDlqWhileBelowTheLimit() throws Exception {
        start(true, -1, delivery(9L)); // -1 means 10 retries with DLQ

        verify(channel, timeout(2_000)).basicNack(DELIVERY_TAG, false, false);
        verify(discardNotifier, after(200).never()).tryNotifyDiscard(any(Message.class));
    }

    @Test
    void dlqRetryDiscardsTheMessageOnceTheLimitIsReached() throws Exception {
        start(true, -1, delivery(10L));

        verify(channel, timeout(2_000)).basicAck(DELIVERY_TAG, false);
        verify(discardNotifier).tryNotifyDiscard(any(Message.class));
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void aDiscardWhoseNotificationFailsIsRequeuedInsteadOfAcknowledged() throws Exception {
        start(false, 0, delivery(null), Mono.error(new IllegalStateException("broker blocked")));

        verify(channel, timeout(2_000)).basicNack(DELIVERY_TAG, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertThat(counter("reactive_commons.consumer.discard.notify.failed")).isEqualTo(1.0);
    }

    @Test
    void aHandlerThatExceedsItsTimeoutIsCounted() throws Exception {
        stubCollaborators(Mono.empty());
        when(receiver.consumeManualAck(anyString(), any(ConsumeOptions.class)))
                .thenReturn(Flux.just(delivery(null)).concatWith(Flux.never()));

        new HangingListener(new ReactiveMessageListener(receiver, topologyCreator, 250, 250,
                new SubscriberRegistry(), registry, DOMAIN, Duration.ofMillis(100)), discardNotifier, errorReporter)
                .startListener();

        verify(channel, timeout(2_000)).basicNack(DELIVERY_TAG, false, true);
        assertThat(counter("reactive_commons.consumer.handler.timeout")).isEqualTo(1.0);
    }

    private void start(boolean withDLQRetry, long maxRetries, AcknowledgableDelivery delivery) {
        start(withDLQRetry, maxRetries, delivery, Mono.empty());
    }

    private void start(boolean withDLQRetry, long maxRetries, AcknowledgableDelivery delivery,
                       Mono<Void> discardNotification) {
        stubCollaborators(discardNotification);
        // one delivery, then the consumer stays subscribed
        when(receiver.consumeManualAck(anyString(), any(ConsumeOptions.class)))
                .thenReturn(Flux.just(delivery).concatWith(Flux.never()));
        new FailingListener(new ReactiveMessageListener(receiver, topologyCreator, 250, 250,
                new SubscriberRegistry(), registry, DOMAIN, null), withDLQRetry, maxRetries,
                discardNotifier, errorReporter, handlerCalls).startListener();
    }

    private void stubCollaborators(Mono<Void> discardNotification) {
        lenient().when(discardNotifier.tryNotifyDiscard(any(Message.class))).thenReturn(discardNotification);
        lenient().when(errorReporter.reportError(any(Throwable.class), any(Message.class), any(Object.class),
                anyBoolean())).thenReturn(Mono.empty());
    }

    private double counter(String name) {
        return registry.find(name).tags("domain", DOMAIN, "queue", QUEUE).counter().count();
    }

    /**
     * @param deadLetterings the {@code x-death} count of the message, or null when it was never dead-lettered.
     *                       Built with the types the RabbitMQ client decodes AMQP tables and arrays into.
     */
    private AcknowledgableDelivery delivery(Long deadLetterings) {
        Map<String, Object> headers = new HashMap<>();
        if (deadLetterings != null) {
            HashMap<String, Object> death = new HashMap<>();
            death.put("count", deadLetterings);
            death.put("queue", "test-queue");
            headers.put("x-death", new ArrayList<>(List.of(death)));
        }
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .messageId("message-1")
                .headers(headers)
                .build();
        Delivery delivery = new Delivery(new Envelope(DELIVERY_TAG, false, "exchange", "rk"), properties,
                "{}".getBytes());
        return new AcknowledgableDelivery(delivery, channel, (context, error) -> {
            throw new IllegalStateException(error);
        });
    }

    private static class FailingListener extends GenericMessageListener {
        private final AtomicInteger handlerCalls;

        FailingListener(ReactiveMessageListener listener, boolean useDLQRetries, long maxRetries,
                        DiscardNotifier discardNotifier, CustomReporter errorReporter, AtomicInteger handlerCalls) {
            super("test-queue", listener, useDLQRetries, false, maxRetries, RETRY_DELAY, discardNotifier,
                    "command", errorReporter);
            this.handlerCalls = handlerCalls;
        }

        @Override
        protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
            return message -> Mono.defer(() -> {
                handlerCalls.incrementAndGet();
                return Mono.error(new IllegalStateException("handler failure"));
            });
        }

        @Override
        protected String getExecutorPath(AcknowledgableDelivery msj) {
            return "test-path";
        }

        @Override
        protected Object parseMessageForReporter(Message msj) {
            return new Object();
        }

        @Override
        protected String getKind() {
            return "test";
        }
    }

    private static class HangingListener extends GenericMessageListener {

        HangingListener(ReactiveMessageListener listener, DiscardNotifier discardNotifier,
                        CustomReporter errorReporter) {
            super(QUEUE, listener, false, false, -1, RETRY_DELAY, discardNotifier, "command", errorReporter);
        }

        @Override
        protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
            return message -> Mono.never(); // its handlerTimeout must fire
        }

        @Override
        protected String getExecutorPath(AcknowledgableDelivery msj) {
            return "test-path";
        }

        @Override
        protected Object parseMessageForReporter(Message msj) {
            return new Object();
        }

        @Override
        protected String getKind() {
            return "test";
        }
    }
}
