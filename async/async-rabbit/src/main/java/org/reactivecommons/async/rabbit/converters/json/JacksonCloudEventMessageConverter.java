package org.reactivecommons.async.rabbit.converters.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;
import lombok.Data;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;
import org.reactivecommons.async.rabbit.RabbitMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class JacksonCloudEventMessageConverter implements MessageConverter {
    private static final String CONTENT_TYPE = "application/json";
    public static final String FAILED_TO_CONVERT_MESSAGE_CONTENT = "Failed to convert Message content";

    private final ObjectMapper objectMapper;


    public JacksonCloudEventMessageConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.objectMapper.registerModule(JsonFormat.getCloudEventJacksonModule());
    }

    @Override
    public <T> AsyncQuery<T> readAsyncQuery(Message message, Class<T> bodyClass) {
        try {
            final AsyncQueryJson asyncQueryJson = readValue(message, AsyncQueryJson.class);
            T value = extractData(bodyClass, asyncQueryJson.getQueryData());
            return new AsyncQuery<>(asyncQueryJson.getResource(), value);
        } catch (IOException e) {
            throw new MessageConversionException(FAILED_TO_CONVERT_MESSAGE_CONTENT, e);
        }
    }

    @Override
    public <T> DomainEvent<T> readDomainEvent(Message message, Class<T> bodyClass) {
        try {
            final DomainEventJson domainEventJson = readValue(message, DomainEventJson.class);

            T value = extractData(bodyClass, domainEventJson.getData());

            return new DomainEvent<>(domainEventJson.getName(), domainEventJson.getEventId(), value);
        } catch (IOException e) {
            throw new MessageConversionException(FAILED_TO_CONVERT_MESSAGE_CONTENT, e);
        }
    }

    @Override
    public <T> Command<T> readCommand(Message message, Class<T> bodyClass) {
        try {
            final CommandJson commandJson = readValue(message, CommandJson.class);
            T value = extractData(bodyClass, commandJson.getData());
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
    @SuppressWarnings("unchecked")
    public <T> T readValue(Message message, Class<T> valueClass) {
        try {
            if(valueClass == CloudEvent.class){

                return (T) EventFormatProvider
                        .getInstance()
                        .resolveFormat(JsonFormat.CONTENT_TYPE)
                        .deserialize(objectMapper.readValue(message.getBody(), byte[].class));

            }
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


    public Message commandToMessage(Command<CloudEvent> object) {
        byte[] data = EventFormatProvider
                .getInstance()
                .resolveFormat(JsonFormat.CONTENT_TYPE)
                .serialize(object.getData());

        return getRabbitMessage(new Command<>(object.getName(), object.getCommandId(), data));
    }


    public Message eventToMessage(DomainEvent<CloudEvent> object) {
        byte[] data = EventFormatProvider
                .getInstance()
                .resolveFormat(JsonFormat.CONTENT_TYPE)
                .serialize(object.getData());

        return getRabbitMessage(new DomainEvent<>(object.getName(), object.getEventId(), data));
    }


    public Message queryToMessage(AsyncQuery<CloudEvent> object) {
        byte[] data = EventFormatProvider
                .getInstance()
                .resolveFormat(JsonFormat.CONTENT_TYPE)
                .serialize(object.getQueryData());

        return getRabbitMessage(new AsyncQuery<>(object.getResource(), data));
    }
    @Override
    public Message toMessage(Object object) {
        if(object instanceof DomainEvent
                && ((DomainEvent<?>) object).getData() instanceof CloudEvent){
           return eventToMessage((DomainEvent) object);

        }
        if(object instanceof Command
                && ((Command<?>) object).getData() instanceof CloudEvent){
            return commandToMessage((Command) object);
        }
        if(object instanceof AsyncQuery
                && ((AsyncQuery<?>) object).getQueryData() instanceof CloudEvent){
            return queryToMessage((AsyncQuery) object);
        }
        return getRabbitMessage(object);
    }

    private RabbitMessage getRabbitMessage(Object object) {
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

    private <T> T extractData(Class<T> bodyClass, JsonNode node) throws JsonProcessingException {
        T value;
        if(bodyClass == CloudEvent.class){

            value = (T) EventFormatProvider
                    .getInstance()
                    .resolveFormat(JsonFormat.CONTENT_TYPE)
                    .deserialize(Base64.getDecoder()
                            .decode(node.asText()));

        }
        else{
            value = objectMapper.treeToValue(node, bodyClass);
        }
        return value;
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
