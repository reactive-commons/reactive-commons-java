package org.reactivecommons.async.kafka.listeners;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.RawMessage;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueueListener;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOffset;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
class ApplicationTopicListenerTest {

    @Mock
    private ReactiveMessageListener receiver;
    @Mock
    private DiscardNotifier discardNotifier;
    @Mock
    private CustomReporter errorReporter;
    @Mock
    private TopologyCreator topologyCreator;

    @Test
    void shouldUseTheRegisteredNameAsTheTopic() {
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), creator -> Mono.empty());
        ApplicationTopicListener listener = buildListener(registeredListener, true);
        when(receiver.getMaxConcurrency()).thenReturn(1);
        when(receiver.listen(anyString(), any(List.class))).thenReturn(Flux.never());

        listener.startListener(topologyCreator);

        verify(receiver, times(1)).listen(anyString(), eq(List.of("my.custom.topic")));
    }

    @Test
    void shouldUseADedicatedGroupIdPerTopic() {
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), creator -> Mono.empty());
        ApplicationTopicListener listener = buildListener(registeredListener, true, "app-my.custom.topic");
        when(receiver.getMaxConcurrency()).thenReturn(1);
        when(receiver.listen(anyString(), any(List.class))).thenReturn(Flux.never());

        listener.startListener(topologyCreator);

        verify(receiver, times(1)).listen(eq("app-my.custom.topic"), any(List.class));
    }

    @Test
    void shouldDelegateTopologySetupToTheRegisteredHandler() {
        Mono<Void> customSetup = Mono.empty();
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), creator -> customSetup);
        ApplicationTopicListener listener = buildListener(registeredListener, true);
        when(receiver.getMaxConcurrency()).thenReturn(1);
        when(receiver.listen(anyString(), any(List.class))).thenReturn(Flux.never());

        listener.startListener(topologyCreator);

        // The default topic creation is never invoked, the custom setup owns the whole topology
        verify(topologyCreator, never()).createTopics(any(List.class));
    }

    @Test
    void shouldRunTheTopicCreationWhenTheRegisteredSetupRequestsIt() {
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), creator -> ((TopologyCreator) creator).createTopics(List.of("my.custom.topic")));
        ApplicationTopicListener listener = buildListener(registeredListener, true);
        when(receiver.getMaxConcurrency()).thenReturn(1);
        when(receiver.listen(anyString(), any(List.class))).thenReturn(Flux.never());
        when(topologyCreator.createTopics(any(List.class))).thenReturn(Mono.empty());

        listener.startListener(topologyCreator);

        verify(topologyCreator, times(1)).createTopics(List.of("my.custom.topic"));
    }

    @Test
    void shouldNotSetUpTopologyWhenCreateTopologyIsDisabled() {
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), creator -> Mono.empty());
        ApplicationTopicListener listener = buildListener(registeredListener, false);
        when(receiver.getMaxConcurrency()).thenReturn(1);
        when(receiver.listen(anyString(), any(List.class))).thenReturn(Flux.never());

        listener.startListener(topologyCreator);

        verify(topologyCreator, never()).createTopics(any(List.class));
    }

    @Test
    void shouldHandleTheRawMessageWithTheRegisteredHandler() {
        Message message = mock(Message.class);
        final boolean[] handled = {false};
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                m -> Mono.fromRunnable(() -> handled[0] = true), creator -> Mono.empty());
        ApplicationTopicListener listener = buildListener(registeredListener, true);

        Mono<Object> result = listener.rawMessageHandler("my.custom.topic").apply(message);

        StepVerifier.create(result).verifyComplete();
        assertThat(handled[0]).isTrue();
    }

    @Test
    void shouldUseTheTopicAsTheExecutorPath() {
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), creator -> Mono.empty());
        ApplicationTopicListener listener = buildListener(registeredListener, true);
        ReceiverRecord<String, byte[]> receiverRecord = mock(ReceiverRecord.class);

        assertThat(listener.getExecutorPath(receiverRecord)).isEqualTo("my.custom.topic");
    }

    @Test
    void shouldReportTheRawMessageItself() {
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), creator -> Mono.empty());
        ApplicationTopicListener listener = buildListener(registeredListener, true);
        Message message = mock(Message.class);

        assertThat(listener.parseMessageForReporter(message)).isSameAs(message);
    }

    @Test
    void shouldDeliverTheRawMessageEndToEnd() {
        ReceiverRecord<String, byte[]> receiverRecord = mock(ReceiverRecord.class);
        when(receiverRecord.topic()).thenReturn("my.custom.topic");
        when(receiverRecord.value()).thenReturn("payload".getBytes(StandardCharsets.UTF_8));
        when(receiverRecord.headers()).thenReturn(new RecordHeaders());
        when(receiverRecord.key()).thenReturn("key");
        when(receiverRecord.receiverOffset()).thenReturn(mock(ReceiverOffset.class));

        final RawMessage[] received = new RawMessage[1];
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                m -> {
                    received[0] = m;
                    return Mono.empty();
                }, creator -> Mono.empty());
        ApplicationTopicListener listener = buildListener(registeredListener, true);
        when(receiver.getMaxConcurrency()).thenReturn(1);
        when(receiver.listen(anyString(), any(List.class))).thenReturn(Flux.just(receiverRecord));

        listener.startListener(topologyCreator);

        StepVerifier.create(Mono.just(receiverRecord)
                        .flatMap(rec -> listener.handle(rec, java.time.Instant.now())))
                .expectNextCount(1)
                .verifyComplete();
        assertThat(received[0]).isNotNull();
    }

    private ApplicationTopicListener buildListener(RegisteredQueueListener registeredListener, boolean createTopology) {
        return buildListener(registeredListener, createTopology, "app-my.custom.topic");
    }

    private ApplicationTopicListener buildListener(RegisteredQueueListener registeredListener, boolean createTopology,
                                                   String groupId) {
        return new ApplicationTopicListener(
                receiver,
                true,
                createTopology,
                10L,
                10,
                registeredListener,
                discardNotifier,
                errorReporter,
                groupId
        );
    }
}
