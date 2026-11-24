package org.reactivecommons.async.kafka.communications;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.TopicPartition;
import org.reactivecommons.async.kafka.listeners.KafkaListenerMetrics;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import reactor.core.publisher.Flux;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverPartition;
import reactor.kafka.receiver.ReceiverRecord;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.apache.kafka.clients.consumer.ConsumerConfig.CLIENT_ID_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_INSTANCE_ID_CONFIG;


@RequiredArgsConstructor
public class ReactiveMessageListener {
    private final ReceiverOptions<String, byte[]> receiverOptions;
    @Getter
    private final SchemaValidator schemaValidator;
    /**
     * What the listeners that consume through this receiver report.
     */
    @Getter
    private final KafkaListenerMetrics metrics;
    /**
     * Numbers the consumers, so that each one gets a {@code client.id} of its own (see {@link #withClientId}).
     */
    private final AtomicInteger consumers = new AtomicInteger();

    public ReactiveMessageListener(ReceiverOptions<String, byte[]> receiverOptions) {
        this(receiverOptions, NoOpSchemaValidator.INSTANCE);
    }

    public ReactiveMessageListener(ReceiverOptions<String, byte[]> receiverOptions, SchemaValidator schemaValidator) {
        this(receiverOptions, schemaValidator, KafkaListenerMetrics.NONE);
    }

    /**
     * Subscribes a new consumer of the group to the topics.
     *
     * @param flowControl pauses and resumes the partitions of this subscription
     */
    public Flux<ReceiverRecord<String, byte[]>> listen(String groupId, List<String> topics,
                                                       PartitionFlowControl flowControl) {
        ReceiverOptions<String, byte[]> options = withClientId(receiverOptions.consumerProperty(GROUP_ID_CONFIG,
                groupId))
                .subscription(topics)
                .addAssignListener(partitions -> flowControl.assigned());
        return receive(options, flowControl);
    }

    /**
     * Reads the given partitions with a consumer of its own, without a consumer group, even if one is configured:
     * no group is created in the broker and nothing is committed, so where to read from is up to {@code onAssigned}.
     *
     * @param onAssigned positions the partitions (e.g. {@link ReceiverPartition#seekToEnd()}) once assigned, on the
     *                   thread of the consumer
     * @param flowControl pauses and resumes the partitions of this subscription
     */
    public Flux<ReceiverRecord<String, byte[]>> listenAssigned(Collection<TopicPartition> partitions,
                                                               Consumer<Collection<ReceiverPartition>> onAssigned,
                                                               PartitionFlowControl flowControl) {
        Map<String, Object> properties = new HashMap<>(receiverOptions.consumerProperties());
        properties.remove(GROUP_ID_CONFIG);
        properties.remove(GROUP_INSTANCE_ID_CONFIG); // only valid with a group
        // The commit settings do not apply without a group; the client metrics do
        ReceiverOptions<String, byte[]> options = withClientId(ReceiverOptions.<String, byte[]>create(properties)
                .closeTimeout(receiverOptions.closeTimeout())
                .pollTimeout(receiverOptions.pollTimeout())
                .consumerListener(receiverOptions.consumerListener()))
                .assignment(partitions)
                .addAssignListener(onAssigned)
                .addAssignListener(assigned -> flowControl.assigned());
        return receive(options, flowControl);
    }

    /**
     * Each consumer gets the configured {@code client.id} followed by {@code -<n>}: two consumers with the same one
     * would share their JMX beans and metrics (closing one removes those of the other) and could not be told apart in
     * the logs and the quotas of the broker. Without a configured one the client picks a unique one.
     */
    private ReceiverOptions<String, byte[]> withClientId(ReceiverOptions<String, byte[]> options) {
        String clientId = receiverOptions.clientId();
        if (clientId == null || clientId.isEmpty()) {
            return options;
        }
        return options.consumerProperty(CLIENT_ID_CONFIG, clientId + "-" + consumers.incrementAndGet());
    }

    private static Flux<ReceiverRecord<String, byte[]>> receive(ReceiverOptions<String, byte[]> options,
                                                                PartitionFlowControl flowControl) {
        KafkaReceiver<String, byte[]> receiver = KafkaReceiver.create(options);
        flowControl.attach(receiver);
        return receiver.receive();
    }
}
