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

import static org.apache.kafka.clients.consumer.ConsumerConfig.DEFAULT_MAX_POLL_RECORDS;
import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.MAX_POLL_RECORDS_CONFIG;


@RequiredArgsConstructor
public class ReactiveMessageListener {
    private final ReceiverOptions<String, byte[]> receiverOptions;
    @Getter
    private final SchemaValidator schemaValidator;

    public ReactiveMessageListener(ReceiverOptions<String, byte[]> receiverOptions) {
        this(receiverOptions, NoOpSchemaValidator.INSTANCE);
    }

    public Flux<ReceiverRecord<String, byte[]>> listen(String groupId, List<String> topics) { // Notification events
        ReceiverOptions<String, byte[]> options = receiverOptions.consumerProperty(GROUP_ID_CONFIG, groupId);
        return KafkaReceiver.create(options.subscription(topics))
                .receive();
    }

    public int getMaxConcurrency() {
        Object property = receiverOptions.consumerProperty(MAX_POLL_RECORDS_CONFIG);
        if (property instanceof Integer) {
            return (int) property;
        }
        return DEFAULT_MAX_POLL_RECORDS;
    }
}
