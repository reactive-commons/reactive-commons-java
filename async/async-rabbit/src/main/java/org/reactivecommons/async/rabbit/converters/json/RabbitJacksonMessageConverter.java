package org.reactivecommons.async.rabbit.converters.json;

import io.cloudevents.CloudEvent;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.json.JacksonMessageConverter;
import org.reactivecommons.async.rabbit.RabbitMessage;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

public class RabbitJacksonMessageConverter extends JacksonMessageConverter {

    public RabbitJacksonMessageConverter(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public Message toMessage(Object object) {
        if (object instanceof RabbitMessage rabbitMessage) {
            return rabbitMessage;
        }
        byte[] bytes;
        String jsonString = this.objectMapper.writeValueAsString(object);
        bytes = jsonString.getBytes(StandardCharsets.UTF_8);
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
