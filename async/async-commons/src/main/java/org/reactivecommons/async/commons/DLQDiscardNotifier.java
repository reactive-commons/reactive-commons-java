package org.reactivecommons.async.commons;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;
import org.reactivecommons.async.commons.exceptions.SendFailureMessageTooLargeException;
import org.reactivecommons.async.commons.exceptions.SendFailureReturnedException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;

import static org.reactivecommons.async.commons.converters.json.JacksonMessageConverter.APPLICATION_CLOUD_EVENT_JSON;

@Slf4j
@RequiredArgsConstructor
public class DLQDiscardNotifier implements DiscardNotifier {
    /**
     * Name of the discarded messages that cannot be read; they are published as {@code corruptData.dlq}.
     */
    public static final String UNREADABLE_MESSAGES = "corruptData";

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

    /**
     * Publishes the discard notification and fails if it could not be published but publishing it again may succeed
     * (e.g. while the broker blocks the publishing connection). A notification returned by the broker was published,
     * just not routed (no queue is bound to its {@code .dlq} routing key, e.g. nobody listens to the discards), and
     * one too large for the broker can never be: failing for them would requeue the discarded message forever,
     * running its handler again every time.
     */
    @Override
    public Mono<Void> tryNotifyDiscard(Message message) {
        return Mono.defer(() -> notify(message))
                .onErrorResume(error -> causedBy(error, SendFailureReturnedException.class), error -> Mono.empty())
                .onErrorResume(error -> causedBy(error, SendFailureMessageTooLargeException.class), this::onError);
    }

    /**
     * Whether the error or one of its causes is of the given type: the event bus wraps the failures of the sender.
     */
    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
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

    /**
     * Logs the message in a single {@code ERROR} line, with where it comes from, its size and the class of the error;
     * its body, which may carry personal data, and the stack trace only at {@code DEBUG}.
     */
    private Mono<Void> notifyUnreadableMessage(Message message, MessageConversionException e) {
        String bodyString;
        try {
            bodyString = new String(message.getBody(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            bodyString = "Opaque binary Message, unable to decode: " + ex.getMessage();
        }
        Message.Properties properties = message.getProperties();
        log.error("Unable to interpret discarded message {} of topic {} ({} bytes, content type {}): {} (body at "
                        + "DEBUG)", properties == null ? null : sanitize(properties.getKey()),
                properties == null ? null : sanitize(properties.getTopic()),
                message.getBody() == null ? 0 : message.getBody().length,
                properties == null ? null : sanitize(properties.getContentType()), e.getClass().getName());
        if (log.isDebugEnabled()) {
            log.debug("Body of the discarded message that cannot be interpreted: {}", bodyString, e);
        }
        DomainEvent<String> event = new DomainEvent<>(UNREADABLE_MESSAGES + ".dlq", UNREADABLE_MESSAGES, bodyString);
        return Mono.from(eventBus.emit(event));
    }

    /**
     * The identifiers come from the producer of the message: a line break in them would let it forge log lines.
     */
    private static String sanitize(String value) {
        return value == null ? null : value.replace('\r', '_').replace('\n', '_');
    }

    private Mono<Void> onError(Throwable e) {
        log.error("FATAL!! unable to notify Discard of message!!", e);
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
