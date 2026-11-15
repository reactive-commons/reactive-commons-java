package org.reactivecommons.async.kafka.communications;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import reactor.core.publisher.Flux;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;

import java.util.List;

import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;


@RequiredArgsConstructor
public class ReactiveMessageListener {
    private final ReceiverOptions<String, byte[]> receiverOptions;
    @Getter
    private final SchemaValidator schemaValidator;

    public ReactiveMessageListener(ReceiverOptions<String, byte[]> receiverOptions) {
        this(receiverOptions, NoOpSchemaValidator.INSTANCE);
    }

    /**
     * Subscribes a new consumer of the group to the topics.
     *
     * @param flowControl pauses and resumes the partitions of this subscription
     */
    public Flux<ReceiverRecord<String, byte[]>> listen(String groupId, List<String> topics,
                                                       PartitionFlowControl flowControl) {
        ReceiverOptions<String, byte[]> options = receiverOptions.consumerProperty(GROUP_ID_CONFIG, groupId);
        KafkaReceiver<String, byte[]> receiver = KafkaReceiver.create(options.subscription(topics));
        flowControl.attach(receiver);
        return receiver.receive();
    }
}
