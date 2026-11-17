package org.reactivecommons.async.kafka.converters.json;

import io.cloudevents.CloudEvent;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.json.JacksonMessageConverter;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.KafkaMessage.KafkaMessageProperties;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class KafkaJacksonMessageConverter extends JacksonMessageConverter {

    /**
     * The extension of a CloudEvent that the Kafka protocol binding of CloudEvents maps to the key of the record.
     */
    public static final String PARTITION_KEY_EXTENSION = "partitionkey";

    public KafkaJacksonMessageConverter(JsonMapper jsonMapper) {
        super(jsonMapper);
    }

    @Override
    public Message toMessage(Object object) {
        if (object instanceof KafkaMessage kafkaMessage) {
            return kafkaMessage;
        }
        byte[] bytes;
        try {
            String jsonString = this.jsonMapper.writeValueAsString(object);
            bytes = jsonString.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new MessageConversionException(FAILED_TO_CONVERT_MESSAGE_CONTENT, e);
        }
        KafkaMessageProperties props = buildProperties(object);
        return new KafkaMessage(bytes, props, null);
    }

    private KafkaMessageProperties buildProperties(Object message) {
        KafkaMessageProperties props = new KafkaMessageProperties();
        Map<String, Object> headers = new HashMap<>();
        props.setHeaders(headers);

        if (message instanceof CloudEvent cloudEvent) {
            props.setKey(keyOf(cloudEvent));
            props.setTopic(cloudEvent.getType());

            headers.put(CONTENT_TYPE, APPLICATION_CLOUD_EVENT_JSON);
            return props;
        }

        if (message instanceof DomainEvent<?> domainEvent) {
            props.setKey(domainEvent.getEventId());
            props.setTopic(domainEvent.getName());

            headers.put(CONTENT_TYPE, APPLICATION_JSON);
            return props;
        }
        // TODO: Add Command and AsyncQuery support
        throw new IllegalArgumentException("Message type not supported: " + message.getClass().getName());
    }

    /**
     * The key of the record decides its partition, and so the order of the records: a CloudEvent with the
     * {@code partitionkey} extension (e.g. the id of the entity it is about) is keyed by it, as the Kafka binding of
     * CloudEvents maps it, so the events of one entity keep their order. Without it, the event is keyed by its id.
     */
    private static String keyOf(CloudEvent cloudEvent) {
        Object partitionKey = cloudEvent.getExtension(PARTITION_KEY_EXTENSION);
        return partitionKey == null ? cloudEvent.getId() : partitionKey.toString();
    }
}
