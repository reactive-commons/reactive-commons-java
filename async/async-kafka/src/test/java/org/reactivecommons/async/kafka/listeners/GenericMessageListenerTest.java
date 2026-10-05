package org.reactivecommons.async.kafka.listeners;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        verify(topologyCreator, times(1)).createDlqTopics(List.of("topic"));
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

    private void givenAReceivedMessage() {
        ReceiverRecord<String, byte[]> receiverRecord = mock(ReceiverRecord.class);
        when(receiverRecord.topic()).thenReturn("topic");
        when(receiverRecord.value()).thenReturn("message".getBytes(StandardCharsets.UTF_8));
        Headers header = new RecordHeaders().add("contentType", "application/json".getBytes(StandardCharsets.UTF_8));
        when(receiverRecord.headers()).thenReturn(header);
        when(receiverRecord.key()).thenReturn("key");
        // Never-ending flux: a completing one would make GenericMessageListener.onTerminate() resubscribe in a loop
        // and keep the test JVM busy indefinitely
        Flux<ReceiverRecord<String, byte[]>> flux = Flux.just(receiverRecord).concatWith(Flux.never());
        when(receiver.listen(anyString(), any(List.class))).thenReturn(flux);
        when(receiver.getMaxConcurrency()).thenReturn(1);
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
                              long retryDelay, DiscardNotifier discardNotifier, String objectType,
                              CustomReporter customReporter, String groupId, List<String> topics,
                              Function<Message, Mono<Object>> handler) {
            super(listener, useDLQ, createTopology, maxRetries, retryDelay, discardNotifier, objectType, customReporter,
                    groupId, topics);
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
