package org.reactivecommons.async.commons.converters.json;

import io.cloudevents.CloudEvent;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RequiredArgsConstructor
public abstract class JacksonMessageConverter implements MessageConverter {
    private static final String COMMAND_ID_FIELD = "commandId";
    private static final String NAME_FIELD = "name";
    private static final String TYPE_FIELD = "type";
    public static final String FAILED_TO_CONVERT_MESSAGE_CONTENT = "Failed to convert message content";
    public static final String CONTENT_TYPE = "content-type";
    public static final String APPLICATION_CLOUD_EVENT_JSON = "application/cloudevents+json";
    public static final String APPLICATION_JSON = "application/json";

    protected final JsonMapper jsonMapper;

    @Override
    public <T> AsyncQuery<T> readAsyncQuery(Message message, Class<T> bodyClass) {
        final AsyncQueryJson<T> asyncQueryJson = readEnvelope(message, AsyncQueryJson.class, bodyClass);
        return new AsyncQuery<>(asyncQueryJson.getResource(), asyncQueryJson.getQueryData());
    }

    @Override
    public <T> DomainEvent<T> readDomainEvent(Message message, Class<T> bodyClass) {
        final DomainEventJson<T> domainEventJson = readEnvelope(message, DomainEventJson.class, bodyClass);
        return new DomainEvent<>(domainEventJson.getName(), domainEventJson.getEventId(), domainEventJson.getData());
    }

    @Override
    public <T> Command<T> readCommand(Message message, Class<T> bodyClass) {
        final CommandJson<T> commandJson = readEnvelope(message, CommandJson.class, bodyClass);
        return new Command<>(commandJson.getName(), commandJson.getCommandId(), commandJson.getData());
    }

    @Override
    public CloudEvent readCloudEvent(Message message) {
        return readValue(message, CloudEvent.class);
    }

    @Override
    public <T> T readValue(Message message, Class<T> valueClass) {
        return read(message, jsonMapper.constructType(valueClass));
    }

    /**
     * Reads the envelope of a message (event, command or query) with its data bound straight to {@code dataClass},
     * instead of building the JSON tree of the data and converting the tree afterward.
     */
    private <E> E readEnvelope(Message message, Class<?> envelopeClass, Class<?> dataClass) {
        return read(message, jsonMapper.getTypeFactory().constructParametricType(envelopeClass, dataClass));
    }

    private <T> T read(Message message, JavaType type) {
        try {
            return jsonMapper.readValue(message.getBody(), type);
        } catch (Exception e) {
            throw new MessageConversionException(FAILED_TO_CONVERT_MESSAGE_CONTENT, e);
        }
    }

    /**
     * Streams the body instead of building its JSON tree: the scan stops as soon as the command name is known
     * (domain commands are serialized with {@code name} and {@code commandId} before {@code data}) and the content
     * of nested structures is skipped without being materialized.
     */
    @Override
    public String readCommandName(Message message) {
        try (JsonParser parser = jsonMapper.createParser(message.getBody())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            boolean hasCommandId = false;
            String name = null;
            String type = null;
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                final String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case COMMAND_ID_FIELD -> hasCommandId = true;
                    case NAME_FIELD -> name = textOf(parser);
                    case TYPE_FIELD -> type = textOf(parser);
                    default -> {
                        // not needed
                    }
                }
                parser.skipChildren();
                if (hasCommandId && name != null) {
                    return name;
                }
            }
            return type;
        } catch (Exception e) {
            throw new MessageConversionException(FAILED_TO_CONVERT_MESSAGE_CONTENT, e);
        }
    }

    /**
     * Text of the current value, with the same result as {@link JsonNode#asString()}, which cannot coerce objects
     * and arrays to text.
     */
    private static String textOf(JsonParser parser) {
        final JsonToken token = parser.currentToken();
        if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
            throw new IllegalStateException("The name or type of the message is not a text value");
        }
        final String text = parser.getValueAsString();
        return text != null ? text : ""; // a JSON null is an empty text, as in JsonNode#asString()
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Command<T> readCommandStructure(Message message) {
        final CommandJson<JsonNode> commandJson = readEnvelope(message, CommandJson.class, JsonNode.class);
        return new Command<>(commandJson.getName(), commandJson.getCommandId(), (T) commandJson.getData());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> DomainEvent<T> readDomainEventStructure(Message message) {
        final DomainEventJson<JsonNode> eventJson = readEnvelope(message, DomainEventJson.class, JsonNode.class);
        return new DomainEvent<>(eventJson.getName(), eventJson.getEventId(), (T) eventJson.getData());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> AsyncQuery<T> readAsyncQueryStructure(Message message) {
        final AsyncQueryJson<JsonNode> asyncQueryJson = readEnvelope(message, AsyncQueryJson.class, JsonNode.class);
        return new AsyncQuery<>(asyncQueryJson.getResource(), (T) asyncQueryJson.getQueryData());
    }

    @Data
    private static class AsyncQueryJson<T> {
        private String resource;
        private T queryData;
    }

    @Data
    private static class DomainEventJson<T> {
        private String name;
        private String eventId;
        private T data;
    }

    @Data
    private static class CommandJson<T> {
        private String name;
        private String commandId;
        private T data;
    }
}
