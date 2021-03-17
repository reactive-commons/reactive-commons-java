package org.reactivecommons.async.impl.converters.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.impl.RabbitMessage;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;

import java.io.IOException;
import java.nio.charset.Charset;

public class JacksonMessageConverter implements MessageConverter {
    private static final String ENCODING = Charset.defaultCharset().name();
    private static final String CONTENT_TYPE = "application/json";

    private final ObjectMapper objectMapper;


    public JacksonMessageConverter(ObjectMapper objectMapper) {
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

    @Override
    public <T> T readValue(Message message, Class<T> valueClass) {
        try {
            return objectMapper.readValue(message.getBody(), valueClass);
        } catch (IOException e) {
            throw new MessageConversionException("Failed to convert Message content", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Command<T> readCommandStructure(Message message) {
        final CommandJson commandJson = readValue(message, CommandJson.class);
        return new Command<>(commandJson.getName(), commandJson.getCommandId(), (T)commandJson.getData());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> DomainEvent<T> readDomainEventStructure(Message message) {
        final DomainEventJson eventJson = readValue(message, DomainEventJson.class);
        return new DomainEvent<>(eventJson.getName(), eventJson.getEventId(), (T)eventJson.getData());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> AsyncQuery<T> readAsyncQueryStructure(Message message) {
        final AsyncQueryJson asyncQueryJson = readValue(message, AsyncQueryJson.class);
        return new AsyncQuery<>(asyncQueryJson.getResource(), (T)asyncQueryJson.getQueryData());
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
