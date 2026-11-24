package org.reactivecommons.async.kafka;

import io.cloudevents.CloudEvent;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.api.domain.RawMessage;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.Map;

public class KafkaDomainEventBus implements DomainEventBus {
    public static final String NOT_IMPLEMENTED_YET = "Not implemented yet";
    private final ReactiveMessageSender sender;
    private final MessageConverter converter;

    public KafkaDomainEventBus(ReactiveMessageSender sender) {
        this(sender, null);
    }

    /**
     * An event bus that also adds to the events it publishes the {@link KafkaDlqHeaders} that a listener puts in the
     * subscriber context: the one the discard notifier publishes the discarded messages with.
     *
     * @param converter converts an event to the record it is published as, so the headers can be added to it
     */
    public KafkaDomainEventBus(ReactiveMessageSender sender, MessageConverter converter) {
        this.sender = sender;
        this.converter = converter;
    }

    @Override
    public <T> Publisher<Void> emit(DomainEvent<T> event) {
        return send(event);
    }

    @Override
    public <T> Publisher<Void> emit(String domain, DomainEvent<T> event) {
        return notImplemented();
    }

    @Override
    public Publisher<Void> emit(CloudEvent event) {
        return send(event);
    }

    @Override
    public Publisher<Void> emit(String domain, CloudEvent event) {
        return notImplemented();
    }

    @Override
    public Publisher<Void> emit(RawMessage event) {
        return sender.send(event);
    }

    @Override
    public Publisher<Void> emit(String domain, RawMessage event) {
        return notImplemented();
    }

    /**
     * The error is given through {@code onError}, not thrown, so the error handlers of the caller's chain see it.
     */
    private static Mono<Void> notImplemented() {
        return Mono.error(() -> new UnsupportedOperationException(NOT_IMPLEMENTED_YET));
    }

    private Mono<Void> send(Object event) {
        if (converter == null) {
            return sender.send(event);
        }
        return Mono.deferContextual(context -> {
            Map<String, Object> dlqHeaders = KafkaDlqHeaders.from(context);
            if (dlqHeaders.isEmpty()) {
                return sender.send(event);
            }
            Message message = converter.toMessage(event);
            if (!(message instanceof KafkaMessage kafkaMessage)) {
                return sender.send(event);
            }
            // The sender publishes a KafkaMessage as it is
            kafkaMessage.getProperties().getHeaders().putAll(dlqHeaders);
            return sender.send(kafkaMessage);
        });
    }
}
