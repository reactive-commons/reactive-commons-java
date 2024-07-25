package org.reactivecommons.async.rabbit.converters.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.json.JacksonMessageConverter;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;
import org.reactivecommons.async.rabbit.RabbitMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class RabbitJacksonMessageConverter extends JacksonMessageConverter {
    private static final String CONTENT_TYPE = "application/json";

    public RabbitJacksonMessageConverter(ObjectMapper objectMapper) {
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
        RabbitMessage.RabbitMessageProperties props = new RabbitMessage.RabbitMessageProperties();
        props.setContentType(CONTENT_TYPE);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setContentLength(bytes.length);
        return new RabbitMessage(bytes, props);
    }
}
