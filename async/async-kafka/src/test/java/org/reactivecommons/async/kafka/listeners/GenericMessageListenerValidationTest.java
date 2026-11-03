package org.reactivecommons.async.kafka.listeners;

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
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.validation.SchemaValidationException;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        listener = new GenericMessageListenerTest.SampleListener(receiver, true, true, 1, 1,
                discardNotifier, "event", customReporter, "appName", List.of("topic"),
                message -> {
                    handlerCalled.set(true);
                    return Mono.empty();
                });
    }

    @Test
    void shouldValidateEveryReceivedMessageBeforeReachingTheHandler() {
        StepVerifier.create(listener.handle(receiverRecord, Instant.now()))
                .expectNext(receiverRecord)
                .verifyComplete();

        verify(schemaValidator, times(1)).validateInbound("topic", PAYLOAD, headers);
        assertThat(handlerCalled).isTrue();
    }

    @Test
    void shouldFailMessageWhenSchemaValidationFails() {
        doThrow(new SchemaValidationException("invalid payload"))
                .when(schemaValidator).validateInbound(anyString(), any(), any());

        StepVerifier.create(listener.handle(receiverRecord, Instant.now()))
                .expectError(SchemaValidationException.class)
                .verify();

        assertThat(handlerCalled).isFalse();
    }

    @Test
    void shouldReportARejectedRecordOnlyWhenItIsDiscarded() {
        SchemaValidationException failure = new SchemaValidationException("invalid payload");
        doThrow(failure).when(schemaValidator).validateInbound(anyString(), any(), any());

        List<ILoggingEvent> whileHandling = recordLogs(() ->
                StepVerifier.create(listener.handle(receiverRecord, Instant.now()))
                        .expectError(SchemaValidationException.class)
                        .verify());

        // handle() only classifies the failure: reporting it is the job of the fallback, which also logs the
        // headers, the body and the strategy applied, so the stack trace is not written twice per record
        assertThat(whileHandling)
                .noneMatch(event -> thrown(event) instanceof SchemaValidationException)
                .noneMatch(event -> event.getFormattedMessage().contains("Outer error protection"));

        List<ILoggingEvent> whileDiscarding = recordLogs(() ->
                listener.logError(failure, receiverRecord, FallbackStrategy.DEFINITIVE_DISCARD));

        assertThat(whileDiscarding).filteredOn(event -> thrown(event) == failure).hasSize(1);
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
