package org.reactivecommons.async.kafka.listeners;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"rawtypes", "unchecked"})
@ExtendWith(MockitoExtension.class)
class GenericMessageListenerTest {

    @Mock
    private ReactiveMessageListener receiver;
    @Mock
    private Message message;
    @Mock
    private TopologyCreator topologyCreator;

    @Mock
    private RegisteredEventListener<Object, Object> handler;

    SampleListener setup(Function<Message, Mono<Object>> handler) {
        return new SampleListener(
                receiver,
                true,
                true,
                1,
                1,
                mock(DiscardNotifier.class),
                "event",
                mock(CustomReporter.class),
                "appName",
                List.of("topic"),
                handler
        );

    }

    @Test
    void shouldStartListener() {
        // Arrange
        ReceiverRecord<String, byte[]> record = mock(ReceiverRecord.class);
        when(record.topic()).thenReturn("topic");
        when(record.value()).thenReturn("message".getBytes(StandardCharsets.UTF_8));
        Headers header = new RecordHeaders().add("contentType", "application/json".getBytes(StandardCharsets.UTF_8));
        when(record.headers()).thenReturn(header);
        when(record.key()).thenReturn("key");

        Flux<ReceiverRecord<String, byte[]>> flux = Flux.just(record);
        when(receiver.listen(anyString(), any(List.class))).thenReturn(flux);
        when(receiver.getMaxConcurrency()).thenReturn(1);
        when(topologyCreator.createTopics(any(List.class))).thenReturn(Mono.empty());

        final AtomicReference<MonoSink<Object>> sink = new AtomicReference<>();
        Mono<Object> flow = Mono.create(sink::set);
        SampleListener sampleListener = setup(message1 -> {
            sink.get().success("");
            return Mono.empty();
        });
        // Act
        sampleListener.startListener(topologyCreator);
        StepVerifier.create(flow).expectNext("").verifyComplete();
        // Assert
        verify(topologyCreator, times(1)).createTopics(any(List.class));
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
