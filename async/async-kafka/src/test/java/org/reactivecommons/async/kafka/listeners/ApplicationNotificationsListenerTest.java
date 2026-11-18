package org.reactivecommons.async.kafka.listeners;

import io.cloudevents.CloudEvent;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.handlers.CloudEventHandler;
import org.reactivecommons.async.api.handlers.DomainEventHandler;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.RawEventHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.RetryPolicy;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOffset;
import reactor.kafka.receiver.ReceiverPartition;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"rawtypes", "unchecked"})
@ExtendWith(MockitoExtension.class)
class ApplicationNotificationsListenerTest {

    @Mock
    private ReactiveMessageListener receiver;
    @Mock
    private HandlerResolver resolver;
    @Mock
    private MessageConverter messageConverter;
    @Mock
    private Message message;
    @Mock
    private TopologyCreator topologyCreator;

    private ApplicationNotificationsListener applicationNotificationsListener;

    @BeforeEach
    void setup() {
        applicationNotificationsListener = new ApplicationNotificationsListener(new ListenerContext<>(receiver,
                resolver, messageConverter, new RetryPolicy(true, 3, 1000), true, null, null));
    }

    @Test
    void shouldHandleRawMessageSuccessfully() {
        DomainEvent<String> event = new DomainEvent<>("sample", "id", "data");
        DomainEventHandler domainEventHandler = mock(DomainEventHandler.class);
        when(domainEventHandler.handle(event)).thenReturn(Mono.just("Handled"));
        RegisteredEventListener<Object, Object> registeredEventListenerMock = mock(RegisteredEventListener.class);
        when(registeredEventListenerMock.handler()).thenReturn(domainEventHandler);
        when(registeredEventListenerMock.inputClass()).thenReturn(Object.class);
        when(resolver.getNotificationListener(anyString())).thenReturn(registeredEventListenerMock);
        when(messageConverter.readDomainEvent(any(Message.class), any(Class.class))).thenReturn(event);

        Mono<Object> flow = applicationNotificationsListener.rawMessageHandler("executorPath").apply(message);

        StepVerifier.create(flow)
                .expectNext("Handled")
                .verifyComplete();

        verify(resolver, times(1)).getNotificationListener(anyString());
        verify(messageConverter, times(1)).readDomainEvent(any(Message.class), any(Class.class));
    }

    @Test
    void shouldHandleRawMessageSuccessfullyWhenCloudEvent() {
        CloudEvent event = mock(CloudEvent.class);
        EventHandler domainEventHandler = mock(CloudEventHandler.class);
        when(domainEventHandler.handle(event)).thenReturn(Mono.empty());
        RegisteredEventListener<Object, Object> registeredEventListenerMock = mock(RegisteredEventListener.class);
        when(registeredEventListenerMock.handler()).thenReturn(domainEventHandler);
        when(resolver.getNotificationListener(anyString())).thenReturn(registeredEventListenerMock);
        when(messageConverter.readCloudEvent(any(Message.class))).thenReturn(event);

        Mono<Object> flow = applicationNotificationsListener.rawMessageHandler("executorPath").apply(message);

        StepVerifier.create(flow)
                .verifyComplete();

        verify(resolver, times(1)).getNotificationListener(anyString());
        verify(messageConverter, times(1)).readCloudEvent(any(Message.class));
    }

    /**
     * Every instance gets every notification: each one reads every partition by itself, without a consumer group, from
     * the end of the partitions when it starts.
     */
    @Test
    void shouldReadEveryPartitionWithoutAConsumerGroupFromTheEnd() {
        TopicPartition first = new TopicPartition("notification.one", 0);
        TopicPartition second = new TopicPartition("notification.one", 1);
        when(resolver.getNotificationNames()).thenReturn(List.of("notification.one"));
        when(topologyCreator.partitionsOf(List.of("notification.one"))).thenReturn(Mono.just(List.of(first, second)));
        when(receiver.listenAssigned(any(), any(), any())).thenReturn(Flux.never());
        ApplicationNotificationsListener listener = notificationsListener();

        listener.startListener(topologyCreator);

        try {
            ArgumentCaptor<Consumer<Collection<ReceiverPartition>>> onAssigned =
                    ArgumentCaptor.forClass(Consumer.class);
            verify(receiver).listenAssigned(eq(List.of(first, second)), onAssigned.capture(), any());
            verify(receiver, never()).listen(any(), any(), any());
            ReceiverPartition firstAssigned = assigned(first, 7);
            ReceiverPartition secondAssigned = assigned(second, 3);
            onAssigned.getValue().accept(List.of(firstAssigned, secondAssigned));
            verify(firstAssigned).seekToEnd();
            verify(secondAssigned).seekToEnd();
        } finally {
            listener.stop();
        }
    }

    /**
     * Nothing is committed without a consumer group: the position of each partition is kept in memory, so a new
     * subscription after a failure goes on from there instead of skipping what was published meanwhile, and reads a
     * partition added since the start from its beginning.
     */
    @Test
    void shouldGoOnFromWhereItWasWhenItSubscribesAgainAndReadANewPartitionFromItsBeginning() {
        TopicPartition first = new TopicPartition("notification.one", 0);
        TopicPartition added = new TopicPartition("notification.one", 1);
        when(resolver.getNotificationNames()).thenReturn(List.of("notification.one"));
        when(topologyCreator.partitionsOf(List.of("notification.one")))
                .thenReturn(Mono.just(List.of(first)), Mono.just(List.of(first, added)));
        ReceiverPartition firstAssigned = assigned(first, 5);
        ReceiverPartition firstAssignedAgain = assigned(first, 6);
        ReceiverPartition addedAssigned = assigned(added, 0);
        // A tombstone needs no handler: it is skipped, and acknowledged as handled
        ReceiverRecord<String, byte[]> handled = mock(ReceiverRecord.class);
        ReceiverOffset offset = mock(ReceiverOffset.class);
        lenient().when(handled.receiverOffset()).thenReturn(offset);
        when(handled.topic()).thenReturn("notification.one");
        when(handled.partition()).thenReturn(0);
        when(handled.offset()).thenReturn(5L);
        AtomicInteger subscriptions = new AtomicInteger();
        when(receiver.listenAssigned(any(), any(), any())).thenAnswer(invocation -> {
            Consumer<Collection<ReceiverPartition>> onAssigned = invocation.getArgument(1);
            if (subscriptions.incrementAndGet() == 1) {
                onAssigned.accept(List.of(firstAssigned));
                return Flux.just(handled).concatWith(Mono.delay(Duration.ofMillis(50))
                        .then(Mono.error(new KafkaException("Broker may not be available"))));
            }
            onAssigned.accept(List.of(firstAssignedAgain, addedAssigned));
            return Flux.never();
        });
        ApplicationNotificationsListener listener = notificationsListener();

        listener.startListener(topologyCreator);

        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> subscriptions.get() == 2);
            verify(firstAssigned).seekToEnd();
            verify(firstAssignedAgain).seek(6L);
            verify(addedAssigned).seekToBeginning();
            verify(offset, never()).acknowledge();
        } finally {
            listener.stop();
        }
    }

    private ApplicationNotificationsListener notificationsListener() {
        return new ApplicationNotificationsListener(new ListenerContext<>(receiver, resolver, messageConverter,
                new RetryPolicy(false, -1, 10), false, null, null));
    }

    private static ReceiverPartition assigned(TopicPartition partition, long position) {
        ReceiverPartition assigned = mock(ReceiverPartition.class);
        lenient().when(assigned.topicPartition()).thenReturn(partition);
        lenient().when(assigned.position()).thenReturn(position);
        return assigned;
    }

    @Test
    void shouldHandleRawMessageSuccessfullyWhenRawEventHandler() {
        EventHandler rawEventHandler = mock(RawEventHandler.class);
        when(rawEventHandler.handle(message)).thenReturn(Mono.empty());
        RegisteredEventListener<Object, Object> registeredEventListenerMock = mock(RegisteredEventListener.class);
        when(registeredEventListenerMock.handler()).thenReturn(rawEventHandler);
        when(resolver.getNotificationListener(anyString())).thenReturn(registeredEventListenerMock);

        Mono<Object> flow = applicationNotificationsListener.rawMessageHandler("executorPath").apply(message);

        StepVerifier.create(flow)
                .verifyComplete();

        verify(resolver, times(1)).getNotificationListener(anyString());
        verify(rawEventHandler, times(1)).handle(message);
    }
}
