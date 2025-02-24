package org.reactivecommons.async.rabbit.converters.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.json.JacksonMessageConverter;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;
import org.reactivecommons.async.rabbit.RabbitMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class RabbitJacksonMessageConverter extends JacksonMessageConverter {

    public RabbitJacksonMessageConverter(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public Message toMessage(Object object) {
        if (object instanceof RabbitMessage) {
            return (RabbitMessage) object;
        }
        byte[] bytes;
        try {
            String jsonString = this.objectMapper.writeValueAsString(object);
            bytes = jsonString.getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MessageConversionException(FAILED_TO_CONVERT_MESSAGE_CONTENT, e);
        }
        RabbitMessage.RabbitMessageProperties props = new RabbitMessage.RabbitMessageProperties();
        if (object instanceof CloudEvent) {
            props.setContentType(APPLICATION_CLOUD_EVENT_JSON);
        } else {
            props.setContentType(APPLICATION_JSON);
        }
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setContentLength(bytes.length);
        return new RabbitMessage(bytes, props, null);
    }
}
