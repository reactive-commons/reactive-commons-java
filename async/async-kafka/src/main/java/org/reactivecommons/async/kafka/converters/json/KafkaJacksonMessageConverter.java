package org.reactivecommons.async.kafka.converters.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.json.JacksonMessageConverter;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.KafkaMessage.KafkaMessageProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class KafkaJacksonMessageConverter extends JacksonMessageConverter {

    public KafkaJacksonMessageConverter(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public Message toMessage(Object object) {
        byte[] bytes;
        try {
            String jsonString = this.objectMapper.writeValueAsString(object);
            bytes = jsonString.getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MessageConversionException(FAILED_TO_CONVERT_MESSAGE_CONTENT, e);
        }
        KafkaMessageProperties props = buildProperties(object);
        return new KafkaMessage(bytes, props);
    }

    private KafkaMessageProperties buildProperties(Object message) {
        KafkaMessageProperties props = new KafkaMessageProperties();
        Map<String, Object> headers = new HashMap<>();
        props.setHeaders(headers);

        if (message instanceof CloudEvent) {
            CloudEvent cloudEvent = (CloudEvent) message;
            props.setKey(cloudEvent.getId());
            props.setTopic(cloudEvent.getType());

            headers.put(CONTENT_TYPE, APPLICATION_CLOUD_EVENT_JSON);
            return props;
        }

        if (message instanceof DomainEvent<?>) {
            DomainEvent<?> domainEvent = (DomainEvent<?>) message;
            props.setKey(domainEvent.getEventId());
            props.setTopic(domainEvent.getName());

            headers.put(CONTENT_TYPE, APPLICATION_JSON);
            return props;
        }
        // TODO: Add Command and AsyncQuery support
        throw new IllegalArgumentException("Message type not supported: " + message.getClass().getName());
    }
}
