package org.reactivecommons.async.commons.converters.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.jackson.JsonFormat;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;

import java.io.IOException;

@RequiredArgsConstructor
public abstract class JacksonMessageConverter implements MessageConverter {
    public static final String FAILED_TO_CONVERT_MESSAGE_CONTENT = "Failed to convert Message content";
    public static final String CONTENT_TYPE = "content-type";
    public static final String APPLICATION_CLOUD_EVENT_JSON = "application/cloudevents+json";
    public static final String APPLICATION_JSON = "application/json";

    protected final ObjectMapper objectMapper;

    @Override
    public <T> AsyncQuery<T> readAsyncQuery(Message message, Class<T> bodyClass) {
        try {
            final AsyncQueryJson asyncQueryJson = readValue(message, AsyncQueryJson.class);
            final T value = objectMapper.treeToValue(asyncQueryJson.getQueryData(), bodyClass);
            return new AsyncQuery<>(asyncQueryJson.getResource(), value);
        } catch (IOException e) {
            throw new MessageConversionException(FAILED_TO_CONVERT_MESSAGE_CONTENT, e);
        }
    }

    @Override
    public <T> DomainEvent<T> readDomainEvent(Message message, Class<T> bodyClass) {
        try {
            final DomainEventJson domainEventJson = readValue(message, DomainEventJson.class);
            final T value = objectMapper.treeToValue(domainEventJson.getData(), bodyClass);
            return new DomainEvent<>(domainEventJson.getName(), domainEventJson.getEventId(), value);
        } catch (IOException e) {
            throw new MessageConversionException(FAILED_TO_CONVERT_MESSAGE_CONTENT, e);
        }
    }

    @Override
    public <T> Command<T> readCommand(Message message, Class<T> bodyClass) {
        try {
            final CommandJson commandJson = readValue(message, CommandJson.class);
            final T value = objectMapper.treeToValue(commandJson.getData(), bodyClass);
            return new Command<>(commandJson.getName(), commandJson.getCommandId(), value);
        } catch (IOException e) {
            throw new MessageConversionException(FAILED_TO_CONVERT_MESSAGE_CONTENT, e);
        }
    }

    @Override
    public CloudEvent readCloudEvent(Message message) {
        return readValue(message, CloudEvent.class);
    }

    @Override
    public <T> T readValue(Message message, Class<T> valueClass) {
        try {
            return objectMapper.readValue(message.getBody(), valueClass);
        } catch (IOException e) {
            throw new MessageConversionException(FAILED_TO_CONVERT_MESSAGE_CONTENT, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Command<T> readCommandStructure(Message message) {
        final CommandJson commandJson = readValue(message, CommandJson.class);
        return new Command<>(commandJson.getName(), commandJson.getCommandId(), (T) commandJson.getData());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> DomainEvent<T> readDomainEventStructure(Message message) {
        final DomainEventJson eventJson = readValue(message, DomainEventJson.class);
        return new DomainEvent<>(eventJson.getName(), eventJson.getEventId(), (T) eventJson.getData());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> AsyncQuery<T> readAsyncQueryStructure(Message message) {
        final AsyncQueryJson asyncQueryJson = readValue(message, AsyncQueryJson.class);
        return new AsyncQuery<>(asyncQueryJson.getResource(), (T) asyncQueryJson.getQueryData());
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
