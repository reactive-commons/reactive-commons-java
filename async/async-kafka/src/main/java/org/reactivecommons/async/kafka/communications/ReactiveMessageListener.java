package org.reactivecommons.async.kafka.communications;

import lombok.AllArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import reactor.core.publisher.Flux;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;

import java.util.List;


@AllArgsConstructor
public class ReactiveMessageListener {
    private final ReceiverOptions<String, byte[]> receiverOptions;

    public Flux<ReceiverRecord<String, byte[]>> listen(String groupId, List<String> topics) { // Notification events
        ReceiverOptions<String, byte[]> options = receiverOptions.consumerProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        return KafkaReceiver.create(options.subscription(topics))
                .receive();
    }

    public int getMaxConcurrency() {
        Object property = receiverOptions.consumerProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG);
        if (property instanceof Integer) {
            return (int) property;
        }
        return ConsumerConfig.DEFAULT_MAX_POLL_RECORDS;
    }
}
