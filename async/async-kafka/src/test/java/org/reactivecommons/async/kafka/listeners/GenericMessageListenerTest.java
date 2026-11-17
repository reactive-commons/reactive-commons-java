package org.reactivecommons.async.kafka.listeners;

import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.RetryPolicy;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.KafkaDlqHeaders;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.kafka.receiver.ReceiverOffset;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
class GenericMessageListenerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private ReactiveMessageListener receiver;
    @Mock
    private TopologyCreator topologyCreator;

    @Test
    void shouldStartListener() {
        // Arrange
        givenAReceivedMessage();
        when(topologyCreator.createTopics(any(List.class))).thenReturn(Mono.empty());
        when(topologyCreator.createDlqTopics(any(List.class))).thenReturn(Mono.empty());
        // Sinks.one() keeps the value even if the handler runs before the StepVerifier subscribes
        Sinks.One<Object> handled = Sinks.one();
        SampleListener sampleListener = listener(true, true, handled);
        // Act
        sampleListener.startListener(topologyCreator);
        // Assert
        StepVerifier.create(handled.asMono()).expectNext("").expectComplete().verify(TIMEOUT);
        verify(topologyCreator, times(1)).createTopics(List.of("topic"));
        verify(topologyCreator, times(1)).createDlqTopics(List.of("topic", "corruptData"));
    }

    @Test
    void shouldNotCreateDlqTopicsWhenDlqIsDisabled() {
        // Arrange
        givenAReceivedMessage();
        when(topologyCreator.createTopics(any(List.class))).thenReturn(Mono.empty());
        Sinks.One<Object> handled = Sinks.one();
        SampleListener sampleListener = listener(false, true, handled);
        // Act
        sampleListener.startListener(topologyCreator);
        // Assert
        StepVerifier.create(handled.asMono()).expectNext("").expectComplete().verify(TIMEOUT);
        verify(topologyCreator, times(1)).createTopics(List.of("topic"));
        verify(topologyCreator, never()).createDlqTopics(any(List.class));
    }

    @Test
    void shouldNotCreateAnyTopicWhenCreateTopologyIsDisabled() {
        // Arrange
        givenAReceivedMessage();
        Sinks.One<Object> handled = Sinks.one();
        SampleListener sampleListener = listener(true, false, handled);
        // Act
        sampleListener.startListener(topologyCreator);
        // Assert
        StepVerifier.create(handled.asMono()).expectNext("").expectComplete().verify(TIMEOUT);
        verify(topologyCreator, never()).createTopics(any(List.class));
        verify(topologyCreator, never()).createDlqTopics(any(List.class));
    }

    @Test
    void shouldRetryAFailedMessageInPlaceUntilItsHandlerSucceeds() {
        // Arrange
        ReceiverOffset offset = givenAReceivedMessage();
        AtomicInteger attempts = new AtomicInteger();
        SampleListener sampleListener = defaultRetryListener(message -> attempts.incrementAndGet() < 3
                ? Mono.error(new IllegalStateException("dependency down"))
                : Mono.empty());
        // Act
        sampleListener.startListener(topologyCreator);
        // Assert
        verify(offset, timeout(TIMEOUT.toMillis())).acknowledge();
        assertThat(attempts).hasValue(3);
        sampleListener.stop();
    }

    @Test
    void shouldNotAcknowledgeAMessageWhileItsHandlerKeepsFailing() {
        // Arrange
        ReceiverOffset offset = givenAReceivedMessage();
        AtomicInteger attempts = new AtomicInteger();
        SampleListener sampleListener = defaultRetryListener(message -> {
            attempts.incrementAndGet();
            return Mono.error(new IllegalStateException("dependency down"));
        });
        // Act
        sampleListener.startListener(topologyCreator);
        // Assert
        await().atMost(TIMEOUT).until(() -> attempts.get() >= 5);
        verify(offset, never()).acknowledge();
        sampleListener.stop();
    }

    @Test
    void shouldSkipAMessageThatCanNeverBeHandledWithoutRetryingIt() {
        // Arrange
        ReceiverOffset offset = givenAReceivedMessage();
        AtomicInteger attempts = new AtomicInteger();
        SampleListener sampleListener = defaultRetryListener(message -> {
            attempts.incrementAndGet();
            return Mono.error(new MessageConversionException("not a valid payload"));
        });
        // Act
        sampleListener.startListener(topologyCreator);
        // Assert
        verify(offset, timeout(TIMEOUT.toMillis())).acknowledge();
        assertThat(attempts).hasValue(1);
        sampleListener.stop();
    }

    @Test
    void shouldAcknowledgeADiscardedMessageOnlyOnceItIsInItsDlq() {
        // Arrange
        ReceiverOffset offset = givenAReceivedMessage();
        DiscardNotifier discardNotifier = mock(DiscardNotifier.class);
        AtomicInteger notifications = new AtomicInteger();
        when(discardNotifier.tryNotifyDiscard(any())).thenReturn(Mono.defer(() -> notifications.incrementAndGet() == 1
                ? Mono.error(new IllegalStateException("broker unreachable"))
                : Mono.empty()));
        SampleListener sampleListener = dlqListener(discardNotifier);
        // Act
        sampleListener.startListener(topologyCreator);
        // Assert
        await().atMost(TIMEOUT).until(() -> notifications.get() >= 1);
        verify(offset, never()).acknowledge();
        verify(offset, timeout(TIMEOUT.toMillis())).acknowledge();
        assertThat(notifications).hasValue(2);
        sampleListener.stop();
    }

    @Test
    void shouldSkipADiscardedMessageTooLargeForItsDlq() {
        // Arrange
        ReceiverOffset offset = givenAReceivedMessage();
        DiscardNotifier discardNotifier = mock(DiscardNotifier.class);
        AtomicInteger notifications = new AtomicInteger();
        when(discardNotifier.tryNotifyDiscard(any())).thenReturn(Mono.defer(() -> {
            notifications.incrementAndGet();
            return Mono.error(new RecordTooLargeException("too large"));
        }));
        SampleListener sampleListener = dlqListener(discardNotifier);
        // Act
        sampleListener.startListener(topologyCreator);
        // Assert
        verify(offset, timeout(TIMEOUT.toMillis())).acknowledge();
        assertThat(notifications).hasValue(1);
        sampleListener.stop();
    }

    @Test
    void shouldSkipATombstoneAndHandleTheNextMessageWithTheSameConsumer() {
        // Arrange: a record without value (a tombstone of a compacted topic) and the one after it
        ReceiverRecord<String, byte[]> tombstone = received("deleted", null, new RecordHeaders());
        ReceiverRecord<String, byte[]> next = received("next", "next".getBytes(StandardCharsets.UTF_8),
                new RecordHeaders());
        givenReceived(tombstone, next);
        List<String> handled = new CopyOnWriteArrayList<>();
        SampleListener sampleListener = defaultRetryListener(message -> {
            handled.add(new String(message.getBody(), StandardCharsets.UTF_8));
            return Mono.empty();
        });
        // Act
        sampleListener.startListener(topologyCreator);
        // Assert: stopped in any case, a listener that lost its flow subscribes again in a loop
        try {
            verify(next.receiverOffset(), timeout(TIMEOUT.toMillis())).acknowledge();
            verify(tombstone.receiverOffset()).acknowledge();
            assertThat(handled).as("messages given to the handler").containsExactly("next");
            verify(receiver, times(1)).listen(anyString(), any(List.class), any());
        } finally {
            sampleListener.stop();
        }
    }

    @Test
    void shouldHandleAMessageWithAHeaderWithoutValueAndTheNextOneWithTheSameConsumer() {
        // Arrange
        ReceiverRecord<String, byte[]> withNullHeader = received("first", "first".getBytes(StandardCharsets.UTF_8),
                new RecordHeaders().add("trace-id", null));
        ReceiverRecord<String, byte[]> next = received("next", "next".getBytes(StandardCharsets.UTF_8),
                new RecordHeaders());
        givenReceived(withNullHeader, next);
        List<String> handled = new CopyOnWriteArrayList<>();
        SampleListener sampleListener = defaultRetryListener(message -> {
            handled.add(new String(message.getBody(), StandardCharsets.UTF_8));
            return Mono.empty();
        });
        // Act
        sampleListener.startListener(topologyCreator);
        // Assert: stopped in any case, a listener that lost its flow subscribes again in a loop
        try {
            verify(next.receiverOffset(), timeout(TIMEOUT.toMillis())).acknowledge();
            verify(withNullHeader.receiverOffset()).acknowledge();
            assertThat(handled).as("messages given to the handler").containsExactly("first", "next");
            verify(receiver, times(1)).listen(anyString(), any(List.class), any());
        } finally {
            sampleListener.stop();
        }
    }

    /**
     * An unacknowledged message is never released by reactor-kafka, which then delays every rebalance of the group
     * by {@code maxDelayRebalance} (60 s): a discarded message must be acknowledged too.
     */
    @Test
    void shouldAcknowledgeAMessageDiscardedAfterItsRetriesWithoutADlq() {
        // Arrange
        ReceiverOffset offset = givenAReceivedMessage();
        AtomicInteger attempts = new AtomicInteger();
        SampleListener sampleListener = new SampleListener(receiver, false, false, 1, 10,
                mock(DiscardNotifier.class), "event", mock(CustomReporter.class), "appName", List.of("topic"),
                message -> {
                    attempts.incrementAndGet();
                    return Mono.error(new IllegalStateException("dependency down"));
                });
        // Act
        sampleListener.startListener(topologyCreator);
        // Assert
        verify(offset, timeout(TIMEOUT.toMillis())).acknowledge();
        assertThat(attempts).as("the first attempt and 1 retry").hasValue(2);
        sampleListener.stop();
    }

    /**
     * {@code Schedulers.parallel()} runs the timers of the whole application: a handler, or the discard of a message,
     * must not run there.
     */
    @Test
    void shouldRunEveryRetryAndTheDiscardOnTheThreadsOfTheListener() {
        // Arrange
        ReceiverOffset offset = givenAReceivedMessage();
        List<String> threads = new CopyOnWriteArrayList<>();
        DiscardNotifier discardNotifier = mock(DiscardNotifier.class);
        when(discardNotifier.tryNotifyDiscard(any())).thenReturn(Mono.fromRunnable(() ->
                threads.add("discard:" + Thread.currentThread().getName())));
        SampleListener sampleListener = new SampleListener(receiver, true, false, 2, 10, discardNotifier, "event",
                mock(CustomReporter.class), "appName", List.of("topic"), message -> {
            threads.add("attempt:" + Thread.currentThread().getName());
            return Mono.error(new IllegalStateException("dependency down"));
        });
        // Act
        sampleListener.startListener(topologyCreator);
        // Assert
        verify(offset, timeout(TIMEOUT.toMillis())).acknowledge();
        assertThat(threads).as("thread of each attempt and of the discard").hasSize(4)
                .allMatch(thread -> thread.contains(":SampleListener-"));
        sampleListener.stop();
    }

    @Test
    void shouldDiscardTheVeryFailureOfAHandlerWhoseMessageSaysRetriesExhausted() {
        // Arrange
        IllegalStateException failure = new IllegalStateException("Retries exhausted by the payment gateway");
        CustomReporter reporter = reporter();
        AtomicInteger attempts = new AtomicInteger();
        ReceiverOffset offset = givenAReceivedMessage();
        SampleListener sampleListener = new SampleListener(receiver, false, false, 1, 10,
                mock(DiscardNotifier.class), "event", reporter, "appName", List.of("topic"), message -> {
            attempts.incrementAndGet();
            return Mono.error(failure);
        });
        // Act
        sampleListener.startListener(topologyCreator);
        // Assert
        verify(offset, timeout(TIMEOUT.toMillis())).acknowledge();
        assertThat(reportedFailure(reporter)).isSameAs(failure);
        assertThat(attempts).hasValue(2);
        sampleListener.stop();
    }

    @Test
    void shouldDiscardWithoutRetriesAMessageThatCannotBeReadWhoseFailureSaysRetriesExhausted() {
        // Arrange: not wrapped by the retries, so its own message reaches the classification of the failure
        MessageConversionException failure = new MessageConversionException("Retries exhausted reading the payload");
        CustomReporter reporter = reporter();
        AtomicInteger attempts = new AtomicInteger();
        ReceiverOffset offset = givenAReceivedMessage();
        SampleListener sampleListener = new SampleListener(receiver, false, false, 3, 10,
                mock(DiscardNotifier.class), "event", reporter, "appName", List.of("topic"), message -> {
            attempts.incrementAndGet();
            return Mono.error(failure);
        });
        // Act
        sampleListener.startListener(topologyCreator);
        // Assert
        verify(offset, timeout(TIMEOUT.toMillis())).acknowledge();
        assertThat(reportedFailure(reporter)).isSameAs(failure);
        assertThat(attempts).as("deliveries to the handler").hasValue(1);
        sampleListener.stop();
    }

    /**
     * The discard notifier publishes a new event built from the message: it gets the headers of the DLQ through the
     * subscriber context.
     */
    @Test
    void shouldGiveTheDiscardNotifierWhereTheMessageCameFromAndWhyItWasDiscarded() {
        // Arrange
        ReceiverOffset offset = givenAReceivedMessage();
        AtomicReference<Map<String, Object>> dlqHeaders = new AtomicReference<>();
        DiscardNotifier discardNotifier = mock(DiscardNotifier.class);
        when(discardNotifier.tryNotifyDiscard(any())).thenReturn(Mono.deferContextual(context -> {
            dlqHeaders.set(KafkaDlqHeaders.from(context));
            return Mono.empty();
        }));
        SampleListener sampleListener = dlqListener(discardNotifier);
        // Act
        sampleListener.startListener(topologyCreator);
        // Assert
        verify(offset, timeout(TIMEOUT.toMillis())).acknowledge();
        assertThat(dlqHeaders.get())
                .containsEntry(KafkaDlqHeaders.ORIGINAL_TOPIC, "topic")
                .containsEntry(KafkaDlqHeaders.ORIGINAL_PARTITION, "0")
                .containsEntry(KafkaDlqHeaders.ORIGINAL_OFFSET, "0")
                .containsEntry(KafkaDlqHeaders.EXCEPTION_CLASS, IllegalStateException.class.getName())
                .containsEntry(KafkaDlqHeaders.EXCEPTION_MESSAGE, "dependency down");
        sampleListener.stop();
    }

    /**
     * Closing the application lets the message being handled finish and commits its offset, so it is not delivered
     * again after the restart; the messages behind it are left for the next consumer.
     */
    @Test
    void shouldDrainByWaitingForTheMessageBeingHandledAndCommittingItsOffset() {
        // Arrange
        ReceiverRecord<String, byte[]> first = received("first", "first".getBytes(StandardCharsets.UTF_8),
                new RecordHeaders());
        ReceiverRecord<String, byte[]> second = received("second", "second".getBytes(StandardCharsets.UTF_8),
                new RecordHeaders());
        when(first.receiverOffset().commit()).thenReturn(Mono.empty());
        givenReceived(first, second);
        Sinks.Empty<Void> started = Sinks.empty();
        List<String> handled = new CopyOnWriteArrayList<>();
        SampleListener sampleListener = defaultRetryListener(message -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            if (body.equals("first")) {
                started.tryEmitEmpty();
                return Mono.delay(Duration.ofMillis(300)).doOnNext(ignored -> handled.add(body)).cast(Object.class);
            }
            handled.add(body);
            return Mono.empty();
        });
        sampleListener.startListener(topologyCreator);
        StepVerifier.create(started.asMono()).expectComplete().verify(TIMEOUT);
        // Act
        StepVerifier.create(sampleListener.drain()).expectComplete().verify(TIMEOUT);
        // Assert
        try {
            assertThat(handled).as("messages handled").containsExactly("first");
            verify(first.receiverOffset()).acknowledge();
            verify(first.receiverOffset()).commit();
            verify(second.receiverOffset(), never()).acknowledge();
        } finally {
            sampleListener.stop();
        }
    }

    @Test
    void shouldDrainWithoutDiscardingAMessageRetriedInPlace() {
        // Arrange
        ReceiverOffset offset = givenAReceivedMessage();
        AtomicInteger attempts = new AtomicInteger();
        SampleListener sampleListener = defaultRetryListener(message -> {
            attempts.incrementAndGet();
            return Mono.error(new IllegalStateException("dependency down"));
        });
        sampleListener.startListener(topologyCreator);
        await().atMost(TIMEOUT).until(() -> attempts.get() >= 2);
        // Act
        StepVerifier.create(sampleListener.drain()).expectComplete().verify(TIMEOUT);
        // Assert: delivered again after the restart, not skipped
        verify(offset, never()).acknowledge();
        sampleListener.stop();
    }

    @Test
    void shouldDrainWithoutSkippingAMessageThatCannotBeSentToItsDlqYet() {
        // Arrange
        ReceiverOffset offset = givenAReceivedMessage();
        DiscardNotifier discardNotifier = mock(DiscardNotifier.class);
        AtomicInteger notifications = new AtomicInteger();
        when(discardNotifier.tryNotifyDiscard(any())).thenReturn(Mono.defer(() -> {
            notifications.incrementAndGet();
            return Mono.error(new IllegalStateException("broker unreachable"));
        }));
        SampleListener sampleListener = dlqListener(discardNotifier);
        sampleListener.startListener(topologyCreator);
        await().atMost(TIMEOUT).until(() -> notifications.get() >= 1);
        // Act
        StepVerifier.create(sampleListener.drain()).expectComplete().verify(TIMEOUT);
        // Assert
        verify(offset, never()).acknowledge();
        sampleListener.stop();
    }

    private static CustomReporter reporter() {
        CustomReporter reporter = mock(CustomReporter.class);
        lenient().when(reporter.reportError(any(Throwable.class), any(), (Object) any(), anyBoolean()))
                .thenReturn(Mono.empty());
        return reporter;
    }

    /**
     * The failure the custom reporter was given when the message was discarded.
     */
    private static Throwable reportedFailure(CustomReporter reporter) {
        ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
        verify(reporter, timeout(TIMEOUT.toMillis())).reportError(failure.capture(), any(), (Object) any(),
                anyBoolean());
        return failure.getValue();
    }

    private ReceiverRecord<String, byte[]> received(String key, byte[] value, Headers headers) {
        ReceiverRecord<String, byte[]> receiverRecord = mock(ReceiverRecord.class);
        ReceiverOffset offset = mock(ReceiverOffset.class);
        lenient().when(receiverRecord.receiverOffset()).thenReturn(offset);
        lenient().when(receiverRecord.topic()).thenReturn("topic");
        lenient().when(receiverRecord.value()).thenReturn(value);
        lenient().when(receiverRecord.headers()).thenReturn(headers);
        lenient().when(receiverRecord.key()).thenReturn(key);
        return receiverRecord;
    }

    private void givenReceived(ReceiverRecord<String, byte[]>... records) {
        // Never-ending flux, see givenAReceivedMessage()
        Flux<ReceiverRecord<String, byte[]>> flux = Flux.just(records).concatWith(Flux.never());
        when(receiver.listen(anyString(), any(List.class), any())).thenReturn(flux);
    }

    /**
     * A listener with a DLQ, whose handler always fails and is not retried.
     */
    private SampleListener dlqListener(DiscardNotifier discardNotifier) {
        return new SampleListener(receiver, true, false, 0, 10, discardNotifier, "event",
                mock(CustomReporter.class), "appName", List.of("topic"),
                message -> Mono.error(new IllegalStateException("dependency down")));
    }

    /**
     * The default retry policy: no DLQ and {@code maxRetries} -1, retrying every 10 ms.
     */
    private SampleListener defaultRetryListener(Function<Message, Mono<Object>> handler) {
        return new SampleListener(receiver, false, false, -1, 10, mock(DiscardNotifier.class), "event",
                mock(CustomReporter.class), "appName", List.of("topic"), handler);
    }

    private ReceiverOffset givenAReceivedMessage() {
        ReceiverRecord<String, byte[]> receiverRecord = mock(ReceiverRecord.class);
        ReceiverOffset offset = mock(ReceiverOffset.class);
        lenient().when(receiverRecord.receiverOffset()).thenReturn(offset);
        when(receiverRecord.topic()).thenReturn("topic");
        when(receiverRecord.value()).thenReturn("message".getBytes(StandardCharsets.UTF_8));
        Headers header = new RecordHeaders().add("contentType", "application/json".getBytes(StandardCharsets.UTF_8));
        when(receiverRecord.headers()).thenReturn(header);
        when(receiverRecord.key()).thenReturn("key");
        // Never-ending flux: a completing one would be subscribed again, and the message handled again
        Flux<ReceiverRecord<String, byte[]>> flux = Flux.just(receiverRecord).concatWith(Flux.never());
        when(receiver.listen(anyString(), any(List.class), any())).thenReturn(flux);
        return offset;
    }

    private SampleListener listener(boolean useDLQ, boolean createTopology, Sinks.One<Object> handled) {
        return new SampleListener(
                receiver,
                useDLQ,
                createTopology,
                1,
                1,
                mock(DiscardNotifier.class),
                "event",
                mock(CustomReporter.class),
                "appName",
                List.of("topic"),
                message -> {
                    handled.tryEmitValue("");
                    return Mono.empty();
                }
        );
    }

    public static class SampleListener extends GenericMessageListener {
        private final Function<Message, Mono<Object>> handler;

        public SampleListener(ReactiveMessageListener listener, boolean useDLQ, boolean createTopology, long maxRetries,
                              int retryDelay, DiscardNotifier discardNotifier, String objectType,
                              CustomReporter customReporter, String groupId, List<String> topics,
                              Function<Message, Mono<Object>> handler) {
            super(new ListenerContext<>(listener, null, null, new RetryPolicy(useDLQ, maxRetries, retryDelay),
                    createTopology, discardNotifier, customReporter), objectType, groupId, topics);
            this.handler = handler;
        }

        @Override
        protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
            return handler;
        }

        @Override
        protected String getExecutorPath(ReceiverRecord<String, byte[]> msj) {
            return msj.topic();
        }

        @Override
        protected Object parseMessageForReporter(Message msj) {
            return null;
        }
    }
}
