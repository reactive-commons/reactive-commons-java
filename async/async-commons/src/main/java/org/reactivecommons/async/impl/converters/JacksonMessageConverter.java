package org.reactivecommons.async.impl.converters;


import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.impl.communications.Message;
import org.reactivecommons.async.impl.exceptions.MessageConversionException;
import org.reactivecommons.async.impl.RabbitMessage;

import java.io.IOException;
import java.nio.charset.Charset;

public class JacksonMessageConverter implements MessageConverter {
    private static final String ENCODING = Charset.defaultCharset().name();
    private static final String CONTENT_TYPE = "application/json";

    private final ObjectMapper objectMapper;

    public JacksonMessageConverter() {
        final ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> AsyncQuery<T> readAsyncQuery(Message message, Class<T> bodyClass) {
        try {
            final AsyncQueryJson asyncQueryJson = objectMapper.readValue(message.getBody(), AsyncQueryJson.class);
            final T value = objectMapper.treeToValue(asyncQueryJson.getQueryData(), bodyClass);
            return new AsyncQuery<>(asyncQueryJson.getResource(), value);
        } catch (IOException e) {
            throw new MessageConversionException("Failed to convert Message content", e);
        }
    }

    @Override
    public <T> DomainEvent<T> readDomainEvent(Message message, Class<T> bodyClass) {
        try {
            final DomainEventJson domainEventJson = objectMapper.readValue(message.getBody(), DomainEventJson.class);
            final T value = objectMapper.treeToValue(domainEventJson.getData(), bodyClass);
            return new DomainEvent<>(domainEventJson.getName(), domainEventJson.getEventId(), value);
        } catch (IOException e) {
            throw new MessageConversionException("Failed to convert Message content", e);
        }
    }

    @Override
    public <T> Command<T> readCommand(Message message, Class<T> bodyClass) {
        try {
            final CommandJson commandJson = objectMapper.readValue(message.getBody(), CommandJson.class);
            final T value = objectMapper.treeToValue(commandJson.getData(), bodyClass);
            return new Command<>(commandJson.getName(), commandJson.getCommandId(), value);
        } catch (IOException e) {
            throw new MessageConversionException("Failed to convert Message content", e);
        }
    }

    //TODO: pull definition up to interface
    public <T> Command<T> readCommandStructure(Message message) {
        try {
            final CommandJson commandJson = objectMapper.readValue(message.getBody(), CommandJson.class);
            return new Command<>(commandJson.getName(), commandJson.getCommandId(), null);
        } catch (IOException e) {
            throw new MessageConversionException("Failed to convert Message content", e);
        }
    }

    @Override
    public Message toMessage(Object object) {
        byte[] bytes;
        try {
            String jsonString = this.objectMapper.writeValueAsString(object);
            bytes = jsonString.getBytes(ENCODING);
        }
        catch (IOException e) {
            throw new MessageConversionException("Failed to convert Message content", e);
        }
        RabbitMessage.RabbitMessageProperties props = new RabbitMessage.RabbitMessageProperties();
        props.setContentType(CONTENT_TYPE);
        props.setContentEncoding(ENCODING);
        props.setContentLength(bytes.length);
        return new RabbitMessage(bytes, props);
    }

    @Data
    private static class AsyncQueryJson {
        private String resource;
        private JsonNode queryData;
    }

    @Data
    private static class DomainEventJson {
        private String name;
        private String eventId;
        private JsonNode data;
    }

    @Data
    private static class CommandJson {
        private String name;
        private String commandId;
        private JsonNode data;
    }
}
