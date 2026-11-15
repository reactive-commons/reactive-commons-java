package org.reactivecommons.async.kafka.communications;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.kafka.communications.PartitionFlowControl.PAUSE_AT;
import static org.reactivecommons.async.kafka.communications.PartitionFlowControl.RESUME_AT;

@SuppressWarnings("unchecked")
class PartitionFlowControlTest {

    private static final TopicPartition WAITING = new TopicPartition("topic", 0);
    private static final TopicPartition OTHER = new TopicPartition("topic", 1);

    private final Consumer<String, byte[]> consumer = mock(Consumer.class);
    private final PartitionFlowControl flowControl = new PartitionFlowControl();

    @BeforeEach
    void setUp() {
        KafkaReceiver<String, byte[]> receiver = mock(KafkaReceiver.class);
        // The receiver runs the action on its consumer, as it does on the thread of the consumer
        when(receiver.doOnConsumer(any())).thenAnswer(invocation -> Mono.fromCallable(() ->
                ((Function<Consumer<String, byte[]>, ?>) invocation.getArgument(0)).apply(consumer)));
        when(consumer.assignment()).thenReturn(Set.of(WAITING, OTHER));
        flowControl.attach(receiver);
    }

    @Test
    void shouldPauseAPartitionOnceTooManyOfItsMessagesWait() {
        receive(WAITING, PAUSE_AT - 1);
        verify(consumer, never()).pause(any());

        receive(WAITING, 1);
        verify(consumer, times(1)).pause(List.of(WAITING));

        receive(WAITING, 100); // messages already on their way when it was paused
        verify(consumer, times(1)).pause(any());
    }

    @Test
    void shouldResumeAPartitionOnceEnoughOfItsMessagesWereHandled() {
        receive(WAITING, PAUSE_AT);

        handle(WAITING, PAUSE_AT - RESUME_AT - 1);
        verify(consumer, never()).resume(any());

        handle(WAITING, 1);
        verify(consumer, times(1)).resume(List.of(WAITING));

        handle(WAITING, RESUME_AT);
        verify(consumer, times(1)).resume(any());
    }

    @Test
    void shouldNotPauseTheOtherPartitions() {
        receive(WAITING, PAUSE_AT);
        receive(OTHER, PAUSE_AT - 1);

        verify(consumer, times(1)).pause(List.of(WAITING));
        verify(consumer, never()).pause(List.of(OTHER));
    }

    @Test
    void shouldPauseAPartitionAgainWhenItsMessagesPileUpAgain() {
        receive(WAITING, PAUSE_AT);
        handle(WAITING, PAUSE_AT - RESUME_AT);
        receive(WAITING, PAUSE_AT - RESUME_AT);

        verify(consumer, times(2)).pause(List.of(WAITING));
        verify(consumer, times(1)).resume(List.of(WAITING));
    }

    @Test
    void shouldNotPauseAPartitionThatIsNoLongerAssigned() {
        when(consumer.assignment()).thenReturn(Set.of(OTHER));

        receive(WAITING, PAUSE_AT);

        verify(consumer, never()).pause(any());
    }

    @Test
    void shouldKeepWorkingWhenTheConsumerRejectsTheResumeOfARevokedPartition() {
        receive(WAITING, PAUSE_AT);
        doThrow(new IllegalStateException("No current assignment for partition topic-0"))
                .when(consumer).resume(List.of(WAITING));

        handle(WAITING, PAUSE_AT - RESUME_AT);
        receive(WAITING, PAUSE_AT - RESUME_AT);

        verify(consumer, times(2)).pause(List.of(WAITING));
    }

    @Test
    void shouldIgnoreTheMessagesReceivedBeforeAReceiverIsAttached() {
        PartitionFlowControl detached = new PartitionFlowControl();
        for (int i = 0; i < PAUSE_AT; i++) {
            detached.received(WAITING);
        }
        verify(consumer, never()).pause(any());
    }

    private void receive(TopicPartition partition, int count) {
        for (int i = 0; i < count; i++) {
            flowControl.received(partition);
        }
    }

    private void handle(TopicPartition partition, int count) {
        for (int i = 0; i < count; i++) {
            flowControl.handled(partition);
        }
    }
}
