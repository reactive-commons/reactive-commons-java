package org.reactivecommons.async.kafka.listeners;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.FallbackStrategy;
import org.reactivecommons.async.commons.RetryPolicy;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.validation.SchemaResolutionException;
import org.reactivecommons.async.kafka.validation.SchemaValidationException;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOffset;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.test.StepVerifier;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
class GenericMessageListenerValidationTest {

    private static final byte[] PAYLOAD = "{}".getBytes(StandardCharsets.UTF_8);

    @Mock
    private ReactiveMessageListener receiver;
    @Mock
    private SchemaValidator schemaValidator;
    @Mock
    private ReceiverRecord<String, byte[]> receiverRecord;
    @Mock
    private DiscardNotifier discardNotifier;
    @Mock
    private CustomReporter customReporter;

    private final AtomicBoolean handlerCalled = new AtomicBoolean(false);
    private final Headers headers = new RecordHeaders().add("contentType",
            "application/json".getBytes(StandardCharsets.UTF_8));

    private GenericMessageListenerTest.SampleListener listener;


    @BeforeEach
    void setUp() {
        when(receiverRecord.topic()).thenReturn("topic");
        when(receiverRecord.value()).thenReturn(PAYLOAD);
        when(receiverRecord.headers()).thenReturn(headers);
        when(receiver.getSchemaValidator()).thenReturn(schemaValidator);
        listener = new GenericMessageListenerTest.SampleListener(receiver,
                new GenericMessageListenerTest.ListenerSettings(new RetryPolicy(true, 1, 1), true, discardNotifier,
                        customReporter, GenericMessageListenerTest.Subscription.DEFAULT),
                message -> {
                    handlerCalled.set(true);
                    return Mono.empty();
                });
    }

    @Test
    void shouldValidateEveryReceivedMessageBeforeReachingTheHandler() {
        StepVerifier.create(listener.handle(receiverRecord, KafkaMessage.fromDelivery(receiverRecord), Instant.now()))
                .expectNext(receiverRecord)
                .verifyComplete();

        verify(schemaValidator, times(1)).validateInbound("topic", PAYLOAD, headers);
        assertThat(handlerCalled).isTrue();
    }

    @Test
    void shouldFailMessageWhenSchemaValidationFails() {
        doThrow(new SchemaValidationException("invalid payload"))
                .when(schemaValidator).validateInbound(anyString(), any(), any());

        StepVerifier.create(listener.handle(receiverRecord, KafkaMessage.fromDelivery(receiverRecord), Instant.now()))
                .expectError(SchemaValidationException.class)
                .verify();

        assertThat(handlerCalled).isFalse();
    }

    @Test
    void shouldReportARejectedRecordOnlyWhenItIsDiscarded() {
        SchemaValidationException failure = new SchemaValidationException("invalid payload");
        doThrow(failure).when(schemaValidator).validateInbound(anyString(), any(), any());

        List<ILoggingEvent> whileHandling = recordLogs(() ->
                StepVerifier.create(listener.handle(receiverRecord, KafkaMessage.fromDelivery(receiverRecord),
                                Instant.now()))
                        .expectError(SchemaValidationException.class)
                        .verify());

        // handle() only classifies the failure: reporting it is the job of the fallback, which also logs the
        // headers, the body and the strategy applied, so the stack trace is not written twice per record
        assertThat(whileHandling)
                .noneMatch(event -> thrown(event) instanceof SchemaValidationException)
                .noneMatch(event -> event.getFormattedMessage().contains("Outer error protection"));

        Logger logger = (Logger) LoggerFactory.getLogger(GenericMessageListener.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG); // the stack trace of a discarded message is logged at DEBUG only
        List<ILoggingEvent> whileDiscarding;
        try {
            whileDiscarding = recordLogs(() ->
                    listener.logError(failure, receiverRecord, FallbackStrategy.DEFINITIVE_DISCARD));
        } finally {
            logger.setLevel(previous);
        }

        assertThat(whileDiscarding).filteredOn(event -> thrown(event) == failure).hasSize(1);
    }

    /**
     * Resolving a schema may block on the registry, so it must not run on the thread of the Kafka consumer, which
     * polls and commits every partition of the listener.
     */
    @Test
    void shouldValidateAReceivedMessageOnTheThreadsOfTheListener() {
        AtomicReference<String> validatedOn = new AtomicReference<>();
        doAnswer(invocation -> {
            validatedOn.set(Thread.currentThread().getName());
            return null;
        }).when(schemaValidator).validateInbound(anyString(), any(), any());

        StepVerifier.create(listener.handle(receiverRecord, KafkaMessage.fromDelivery(receiverRecord), Instant.now()))
                .expectNext(receiverRecord)
                .verifyComplete();

        assertThat(validatedOn.get()).startsWith("SampleListener-");
    }

    /**
     * A registry that cannot be reached says nothing about the message: it is retried like a failed handler, not
     * discarded, and handled once the registry answers again.
     */
    @Test
    void shouldRetryAMessageWhoseSchemaCannotBeResolvedUntilTheRegistryAnswers() {
        ReceiverOffset offset = mock(ReceiverOffset.class);
        when(receiverRecord.receiverOffset()).thenReturn(offset);
        when(receiver.listen(anyString(), any(List.class), any()))
                .thenReturn(Flux.just(receiverRecord).concatWith(Flux.never()));
        AtomicInteger resolutions = new AtomicInteger();
        doAnswer(invocation -> {
            if (resolutions.incrementAndGet() <= 3) {
                throw new SchemaResolutionException("Unable to resolve the schema for topic topic",
                        new ConnectException("Connection refused"));
            }
            return null;
        }).when(schemaValidator).validateInbound(anyString(), any(), any());
        GenericMessageListenerTest.SampleListener defaultRetries = new GenericMessageListenerTest.SampleListener(
                receiver, GenericMessageListenerTest.ListenerSettings.defaultRetries(discardNotifier, customReporter),
                message -> {
            handlerCalled.set(true);
            return Mono.empty();
        });

        defaultRetries.startListener(null);

        try {
            verify(offset, timeout(5000)).acknowledge();
            assertThat(handlerCalled).as("handled once the registry answers").isTrue();
            assertThat(resolutions).as("resolutions of the schema").hasValue(4);
            verify(discardNotifier, never()).tryNotifyDiscard(any());
        } finally {
            defaultRetries.stop();
        }
    }

    private List<ILoggingEvent> recordLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(GenericMessageListener.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        try {
            action.run();
        } finally {
            logger.detachAppender(logged);
        }
        return logged.list;
    }

    private static Throwable thrown(ILoggingEvent event) {
        return event.getThrowableProxy() instanceof ThrowableProxy proxy ? proxy.getThrowable() : null;
    }
}
