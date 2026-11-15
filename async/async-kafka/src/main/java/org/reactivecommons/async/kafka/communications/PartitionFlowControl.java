package org.reactivecommons.async.kafka.communications;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import reactor.kafka.receiver.KafkaReceiver;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Limits, for each partition of a subscription, the messages received and not handled yet. A partition is paused on
 * the Kafka consumer when {@link #PAUSE_AT} of its messages are waiting, and resumed once they are down to
 * {@link #RESUME_AT}. So a partition whose messages wait behind a message still being handled, or retried, holds its
 * messages in memory up to that limit, while the other partitions of the subscription keep being fetched.
 * <p>
 * The consumer goes on fetching the messages of a paused partition already on their way (up to two batches of
 * {@code max.poll.records}), so the limit is approximate.
 */
@Slf4j
public final class PartitionFlowControl {

    public static final int PAUSE_AT = 256;
    public static final int RESUME_AT = PAUSE_AT / 2;

    // Guarded by this: the pauses and resumes are scheduled on the thread of the consumer in the order they are
    // decided, so a resume can never overtake the pause it undoes
    private final Map<TopicPartition, Integer> waiting = new HashMap<>();
    private final Set<TopicPartition> paused = new HashSet<>();
    private volatile KafkaReceiver<String, byte[]> receiver;

    void attach(KafkaReceiver<String, byte[]> receiver) {
        this.receiver = receiver;
    }

    /**
     * A message of the partition was received: it waits until {@link #handled(TopicPartition)}.
     */
    public synchronized void received(TopicPartition partition) {
        int count = waiting.merge(partition, 1, Integer::sum);
        if (count >= PAUSE_AT && paused.add(partition)) {
            onConsumer("Paused", partition, consumer -> {
                // A partition revoked meanwhile is no longer assigned, and it cannot be paused
                if (consumer.assignment().contains(partition)) {
                    consumer.pause(List.of(partition));
                }
            });
        }
    }

    /**
     * A message of the partition was handled, whatever the outcome.
     */
    public synchronized void handled(TopicPartition partition) {
        int count = waiting.merge(partition, -1, Integer::sum);
        if (count <= 0) {
            waiting.remove(partition);
        }
        if (count <= RESUME_AT && paused.remove(partition)) {
            // Resumed even when no longer assigned: the receiver forgets the pause before the consumer rejects it,
            // so the partition is not paused again if it is assigned later
            onConsumer("Resumed", partition, consumer -> consumer.resume(List.of(partition)));
        }
    }

    private void onConsumer(String action, TopicPartition partition,
                            java.util.function.Consumer<Consumer<String, byte[]>> change) {
        KafkaReceiver<String, byte[]> current = receiver;
        if (current == null) {
            return;
        }
        current.doOnConsumer(consumer -> {
                    change.accept(consumer);
                    return partition;
                })
                .subscribe(done -> log.debug("{} partition {}: messages waiting to be handled", action, done),
                        error -> log.debug("{} partition {} failed: {}", action, partition, error.toString()));
    }
}
