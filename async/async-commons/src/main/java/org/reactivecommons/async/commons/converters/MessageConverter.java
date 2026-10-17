package org.reactivecommons.async.commons.converters;

import io.cloudevents.CloudEvent;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.commons.communications.Message;
import tools.jackson.databind.JsonNode;

public interface MessageConverter {

    <T> AsyncQuery<T> readAsyncQuery(Message message, Class<T> bodyClass);

    <T> DomainEvent<T> readDomainEvent(Message message, Class<T> bodyClass);

    <T> Command<T> readCommand(Message message, Class<T> bodyClass);

    CloudEvent readCloudEvent(Message message);

    <T> T readValue(Message message, Class<T> valueClass);

    /**
     * Name of the handler a command message is addressed to: its {@code name} when it has a {@code commandId}
     * (domain command), otherwise its {@code type} (cloud event). Only the top-level fields are considered.
     *
     * <p>It runs for every received command, so implementations should avoid reading more of the body than needed.
     *
     * @param message the received command
     * @return the name of the command, or {@code null} if the body has neither
     */
    default String readCommandName(Message message) {
        final JsonNode body = readValue(message, JsonNode.class);
        if (body.get("commandId") != null && body.get("name") != null) {
            return body.get("name").asString();
        }
        if (body.get("type") != null) {
            return body.get("type").asString();
        }
        return null;
    }

    <T> Command<T> readCommandStructure(Message message);

    <T> DomainEvent<T> readDomainEventStructure(Message message);

    <T> AsyncQuery<T> readAsyncQueryStructure(Message message);

    Message toMessage(Object object);

}
