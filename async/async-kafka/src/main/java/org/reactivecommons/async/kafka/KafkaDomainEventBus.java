package org.reactivecommons.async.kafka;

import io.cloudevents.CloudEvent;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.api.domain.RawMessage;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivestreams.Publisher;

@RequiredArgsConstructor
public class KafkaDomainEventBus implements DomainEventBus {
    public static final String NOT_IMPLEMENTED_YET = "Not implemented yet";
    private final ReactiveMessageSender sender;

    @Override
    public <T> Publisher<Void> emit(DomainEvent<T> event) {
        return sender.send(event);
    }

    @Override
    public <T> Publisher<Void> emit(String domain, DomainEvent<T> event) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_YET);
    }

    @Override
    public Publisher<Void> emit(CloudEvent event) {
        return sender.send(event);
    }

    @Override
    public Publisher<Void> emit(String domain, CloudEvent event) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_YET);
    }

    @Override
    public Publisher<Void> emit(RawMessage event) {
        return sender.send(event);
    }

    @Override
    public Publisher<Void> emit(String domain, RawMessage event) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_YET);
    }
}
