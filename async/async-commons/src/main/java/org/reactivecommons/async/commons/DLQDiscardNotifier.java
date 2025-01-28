package org.reactivecommons.async.commons;

import com.fasterxml.jackson.databind.JsonNode;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;
import reactor.core.publisher.Mono;

import java.util.logging.Level;

import static java.lang.String.format;
import static org.reactivecommons.async.commons.converters.json.JacksonMessageConverter.APPLICATION_CLOUD_EVENT_JSON;

@Log
@RequiredArgsConstructor
public class DLQDiscardNotifier implements DiscardNotifier {
    private final DomainEventBus eventBus;
    private final MessageConverter messageConverter;

    @Override
    public Mono<Void> notifyDiscard(Message message) {
        try {
            return notify(message).onErrorResume(this::onError);
        } catch (Exception e) {
            return onError(e);
        }
    }

    private Mono<Void> notify(Message message) {
        if (isCloudEvent(message)) {
            CloudEvent cloudEvent = messageConverter.readCloudEvent(message);
            String dlqType = cloudEvent.getType() + ".dlq";
            CloudEvent forDlq = CloudEventBuilder.from(cloudEvent)
                    .withType(dlqType)
                    .build();
            return Mono.from(eventBus.emit(forDlq));
        }
        try {
            JsonSkeleton node = messageConverter.readValue(message, JsonSkeleton.class);
            return Mono.from(eventBus.emit(createEvent(node)));
        } catch (MessageConversionException e) {
            return notifyUnreadableMessage(message, e);
        }
    }

    private Mono<Void> notifyUnreadableMessage(Message message, MessageConversionException e) {
        String bodyString;
        try {
            bodyString = new String(message.getBody());
        } catch (Exception ex) {
            bodyString = "Opaque binary Message, unable to decode: " + ex.getMessage();
        }
        log.log(Level.SEVERE, format("Unable to interpret discarded message: %s", bodyString), e);
        DomainEvent<String> event = new DomainEvent<>("corruptData.dlq", "corruptData", bodyString);
        return Mono.from(eventBus.emit(event));
    }

    private Mono<Void> onError(Throwable e) {
        log.log(Level.SEVERE, "FATAL!! unable to notify Discard of message!!", e);
        return Mono.empty();
    }

    private DomainEvent<JsonNode> createEvent(JsonSkeleton skeleton) {
        if (skeleton.isCommand()) {
            return new DomainEvent<>(skeleton.name + ".dlq", skeleton.commandId, skeleton.data);
        } else if (skeleton.isEvent()) {
            return new DomainEvent<>(skeleton.name + ".dlq", skeleton.eventId, skeleton.data);
        } else if (skeleton.isQuery()) {
            return new DomainEvent<>(skeleton.resource + ".dlq", skeleton.resource + "query", skeleton.queryData);
        } else {
            throw new MessageConversionException("Fail to math message type");
        }
    }

    private boolean isCloudEvent(Message message) {
        return message.getProperties().getContentType() != null
                && message.getProperties().getContentType().contains(APPLICATION_CLOUD_EVENT_JSON);
    }

    @Data
    private static class JsonSkeleton {
        private String name;
        private String resource;
        private String eventId;
        private JsonNode data;
        private JsonNode queryData;
        private String commandId;

        public boolean isEvent() {
            return !empty(eventId) && !empty(name) && data != null;
        }

        public boolean isCommand() {
            return !empty(commandId) && !empty(name) && data != null;
        }

        public boolean isQuery() {
            return !empty(resource) && queryData != null;
        }

        private boolean empty(String str) {
            return str == null || str.trim().isEmpty();
        }
    }

}
