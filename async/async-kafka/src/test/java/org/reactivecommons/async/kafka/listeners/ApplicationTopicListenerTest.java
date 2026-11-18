package org.reactivecommons.async.kafka.listeners;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.RawMessage;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueueListener;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.RetryPolicy;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.KafkaDlqHeaders;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOffset;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
        when(receiver.listen(anyString(), any(List.class), any())).thenReturn(Flux.never());

        listener.startListener(topologyCreator);

        verify(receiver, times(1)).listen(anyString(), eq(List.of("my.custom.topic")), any());
    }

    @Test
    void shouldUseADedicatedGroupIdPerTopic() {
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), creator -> Mono.empty());
        ApplicationTopicListener listener = buildListener(registeredListener, true, "app-my.custom.topic");
        when(receiver.listen(anyString(), any(List.class), any())).thenReturn(Flux.never());

        listener.startListener(topologyCreator);

        verify(receiver, times(1)).listen(eq("app-my.custom.topic"), any(List.class), any());
    }

    @Test
    void shouldDelegateTopologySetupToTheRegisteredHandler() {
        Mono<Void> customSetup = Mono.empty();
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), creator -> customSetup);
        ApplicationTopicListener listener = buildListener(registeredListener, true);
        when(receiver.listen(anyString(), any(List.class), any())).thenReturn(Flux.never());

        listener.startListener(topologyCreator);

        // The default topic creation is never invoked, the custom setup owns the whole topology
        verify(topologyCreator, never()).createTopics(any(List.class));
        // Same as RabbitMQ listenQueue: the DLQ is not created automatically, the custom setup owns it too
        verify(topologyCreator, never()).createDlqTopics(any(List.class));
    }

    @Test
    void shouldCreateTheDlqTopicWhenTheRegisteredSetupRequestsIt() {
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), creator -> ((TopologyCreator) creator).createTopics(List.of("my.custom.topic"))
                .then(((TopologyCreator) creator).createDlqTopics(List.of("my.custom.topic"))));
        ApplicationTopicListener listener = buildListener(registeredListener, true);
        when(receiver.listen(anyString(), any(List.class), any())).thenReturn(Flux.never());
        when(topologyCreator.createTopics(any(List.class))).thenReturn(Mono.empty());
        when(topologyCreator.createDlqTopics(any(List.class))).thenReturn(Mono.empty());

        listener.startListener(topologyCreator);

        verify(topologyCreator, times(1)).createTopics(List.of("my.custom.topic"));
        verify(topologyCreator, times(1)).createDlqTopics(List.of("my.custom.topic"));
    }

    @Test
    void shouldRunTheTopicCreationWhenTheRegisteredSetupRequestsIt() {
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(),
                creator -> ((TopologyCreator) creator).createTopics(List.of("my.custom.topic")));
        ApplicationTopicListener listener = buildListener(registeredListener, true);
        when(receiver.listen(anyString(), any(List.class), any())).thenReturn(Flux.never());
        when(topologyCreator.createTopics(any(List.class))).thenReturn(Mono.empty());

        listener.startListener(topologyCreator);

        verify(topologyCreator, times(1)).createTopics(List.of("my.custom.topic"));
    }

    @Test
    void shouldNotSetUpTopologyWhenCreateTopologyIsDisabled() {
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), creator -> Mono.empty());
        ApplicationTopicListener listener = buildListener(registeredListener, false);
        when(receiver.listen(anyString(), any(List.class), any())).thenReturn(Flux.never());

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
        when(receiverRecord.topic()).thenReturn("my.custom.topic");

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

        final RawMessage[] received = new RawMessage[1];
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                m -> {
                    received[0] = m;
                    return Mono.empty();
                }, creator -> Mono.empty());
        ApplicationTopicListener listener = buildListener(registeredListener, true);
        // Never-ending flux: the record is handled explicitly below
        when(receiver.listen(anyString(), any(List.class), any())).thenReturn(Flux.never());

        listener.startListener(topologyCreator);

        try {
            StepVerifier.create(Mono.just(receiverRecord)
                            .flatMap(rec -> listener.handle(rec, KafkaMessage.fromDelivery(rec),
                                    java.time.Instant.now())))
                    .expectNextCount(1)
                    .verifyComplete();
            assertThat(received[0]).isNotNull();
        } finally {
            listener.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSendADiscardedRecordAsItIsToTheDlqOfItsTopic() {
        ReactiveMessageSender dlqSender = mock(ReactiveMessageSender.class);
        when(dlqSender.send(any())).thenReturn(Mono.empty());
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), creator -> Mono.empty());
        ApplicationTopicListener listener = new ApplicationTopicListener(new ListenerContext<>(receiver, null, null,
                new RetryPolicy(true, 10L, 10), true, discardNotifier, errorReporter), registeredListener,
                "app-my.custom.topic", dlqSender);
        ReceiverRecord<String, byte[]> received = mock(ReceiverRecord.class);
        byte[] body = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
        when(received.key()).thenReturn("order-1");
        when(received.topic()).thenReturn("my.custom.topic");
        when(received.value()).thenReturn(body);
        when(received.headers()).thenReturn(new RecordHeaders()
                .add("trace-id", "abc".getBytes(StandardCharsets.UTF_8))
                .add("no-value", null));
        when(received.partition()).thenReturn(2);
        when(received.offset()).thenReturn(41L);
        Map<String, Object> dlqHeaders = KafkaDlqHeaders.of(received, new IllegalStateException("dependency down"));

        StepVerifier.create(listener.notifyDiscard(KafkaMessage.fromDelivery(received), received, dlqHeaders))
                .verifyComplete();

        ArgumentCaptor<KafkaMessage> sent = ArgumentCaptor.forClass(KafkaMessage.class);
        verify(dlqSender).send(sent.capture());
        assertThat(sent.getValue().getProperties().getTopic()).isEqualTo("my.custom.topic.dlq");
        assertThat(sent.getValue().getProperties().getKey()).isEqualTo("order-1");
        assertThat(sent.getValue().getBody()).isEqualTo(body);
        assertThat(sent.getValue().getProperties().getHeaders())
                .containsEntry("trace-id", "abc")
                .containsEntry("no-value", null)
                .containsEntry(KafkaDlqHeaders.ORIGINAL_TOPIC, "my.custom.topic")
                .containsEntry(KafkaDlqHeaders.ORIGINAL_PARTITION, "2")
                .containsEntry(KafkaDlqHeaders.ORIGINAL_OFFSET, "41")
                .containsEntry(KafkaDlqHeaders.EXCEPTION_CLASS, IllegalStateException.class.getName())
                .containsEntry(KafkaDlqHeaders.EXCEPTION_MESSAGE, "dependency down");
        verify(discardNotifier, never()).tryNotifyDiscard(any());
    }

    /**
     * A raw topic may be a compacted one, where a record without value (a tombstone) deletes its key: the handler
     * receives it, with a {@code null} body, and the records after it.
     */
    @Test
    void shouldGiveATombstoneToTheHandlerWithoutBody() {
        ReceiverRecord<String, byte[]> tombstone = received("deleted", null);
        ReceiverRecord<String, byte[]> next = received("next", "next".getBytes(StandardCharsets.UTF_8));
        when(receiver.listen(anyString(), any(List.class), any()))
                .thenReturn(Flux.just(tombstone, next).concatWith(Flux.never()));
        List<String> bodies = new CopyOnWriteArrayList<>();
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic", message -> {
            byte[] body = ((Message) message).getBody();
            bodies.add(body == null ? null : new String(body, StandardCharsets.UTF_8));
            return Mono.empty();
        }, creator -> Mono.empty());
        ApplicationTopicListener listener = buildListener(registeredListener, false);

        listener.startListener(topologyCreator);

        try {
            verify(next.receiverOffset(), timeout(5000)).acknowledge();
            verify(tombstone.receiverOffset()).acknowledge();
            assertThat(bodies).containsExactly(null, "next");
        } finally {
            listener.stop();
        }
    }

    /**
     * The topics of a domain share one consumer, which gives each record to the handler of its topic.
     */
    @Test
    void shouldListenEveryTopicWithOneConsumerAndGiveEachRecordToTheHandlerOfItsTopic() {
        List<String> handled = new CopyOnWriteArrayList<>();
        ReceiverRecord<String, byte[]> toOne = received("topic.one", "a", "a".getBytes(StandardCharsets.UTF_8));
        ReceiverRecord<String, byte[]> toTwo = received("topic.two", "b", "b".getBytes(StandardCharsets.UTF_8));
        when(receiver.listen(anyString(), any(List.class), any()))
                .thenReturn(Flux.just(toOne, toTwo).concatWith(Flux.never()));
        ApplicationTopicListener listener = new ApplicationTopicListener(context(false),
                List.of(recording("topic.one", handled), recording("topic.two", handled)), "app", null, null);

        listener.startListener(topologyCreator);

        try {
            verify(toOne.receiverOffset(), timeout(5000)).acknowledge();
            verify(toTwo.receiverOffset(), timeout(5000)).acknowledge();
            assertThat(handled).containsExactlyInAnyOrder("topic.one:a", "topic.two:b");
            verify(receiver, times(1)).listen(eq("app"), eq(List.of("topic.one", "topic.two")), any());
        } finally {
            listener.stop();
        }
    }

    @Test
    void shouldRunTheTopologySetupOfEveryTopic() {
        List<String> setUp = new CopyOnWriteArrayList<>();
        RegisteredQueueListener one = new RegisteredQueueListener("topic.one", message -> Mono.empty(),
                creator -> Mono.fromRunnable(() -> setUp.add("topic.one")));
        RegisteredQueueListener two = new RegisteredQueueListener("topic.two", message -> Mono.empty(),
                creator -> Mono.fromRunnable(() -> setUp.add("topic.two")));
        when(receiver.listen(anyString(), any(List.class), any())).thenReturn(Flux.never());
        ApplicationTopicListener listener = new ApplicationTopicListener(context(true), List.of(one, two), "app",
                null, null);

        listener.startListener(topologyCreator);

        try {
            assertThat(setUp).containsExactly("topic.one", "topic.two");
        } finally {
            listener.stop();
        }
    }

    @Test
    void shouldSendADiscardedRecordToTheDlqOfItsOwnTopic() {
        ReactiveMessageSender dlqSender = mock(ReactiveMessageSender.class);
        when(dlqSender.send(any())).thenReturn(Mono.empty());
        ApplicationTopicListener listener = new ApplicationTopicListener(context(true),
                List.of(recording("topic.one", new CopyOnWriteArrayList<>()),
                        recording("topic.two", new CopyOnWriteArrayList<>())), "app", dlqSender, null);
        ReceiverRecord<String, byte[]> received = received("topic.two", "order-1",
                "{}".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> dlqHeaders = Map.of(KafkaDlqHeaders.ORIGINAL_TOPIC, "topic.two");

        StepVerifier.create(listener.notifyDiscard(KafkaMessage.fromDelivery(received), received, dlqHeaders))
                .verifyComplete();

        ArgumentCaptor<KafkaMessage> sent = ArgumentCaptor.forClass(KafkaMessage.class);
        verify(dlqSender).send(sent.capture());
        assertThat(sent.getValue().getProperties().getTopic()).isEqualTo("topic.two.dlq");
    }

    private static RegisteredQueueListener recording(String topic, List<String> handled) {
        return new RegisteredQueueListener(topic, message -> Mono.fromRunnable(() -> handled.add(topic + ":"
                + new String(((Message) message).getBody(), StandardCharsets.UTF_8))), creator -> Mono.empty());
    }

    private ListenerContext<ReactiveMessageListener> context(boolean createTopology) {
        return new ListenerContext<>(receiver, null, null, new RetryPolicy(true, 10L, 10), createTopology,
                discardNotifier, errorReporter);
    }

    private static ReceiverRecord<String, byte[]> received(String topic, String key, byte[] value) {
        ReceiverRecord<String, byte[]> receiverRecord = received(key, value);
        lenient().when(receiverRecord.topic()).thenReturn(topic);
        return receiverRecord;
    }

    private static ReceiverRecord<String, byte[]> received(String key, byte[] value) {
        ReceiverRecord<String, byte[]> receiverRecord = mock(ReceiverRecord.class);
        ReceiverOffset offset = mock(ReceiverOffset.class);
        lenient().when(receiverRecord.receiverOffset()).thenReturn(offset);
        lenient().when(receiverRecord.topic()).thenReturn("my.custom.topic");
        lenient().when(receiverRecord.key()).thenReturn(key);
        lenient().when(receiverRecord.value()).thenReturn(value);
        lenient().when(receiverRecord.headers()).thenReturn(new RecordHeaders());
        return receiverRecord;
    }

    private ApplicationTopicListener buildListener(RegisteredQueueListener registeredListener, boolean createTopology) {
        return buildListener(registeredListener, createTopology, "app-my.custom.topic");
    }

    private ApplicationTopicListener buildListener(RegisteredQueueListener registeredListener, boolean createTopology,
                                                   String groupId) {
        return new ApplicationTopicListener(new ListenerContext<>(receiver, null, null, new RetryPolicy(true, 10L, 10),
                createTopology, discardNotifier, errorReporter), registeredListener, groupId);
    }
}
