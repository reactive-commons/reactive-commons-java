package org.reactivecommons.async.kafka;

import io.cloudevents.CloudEvent;
import lombok.AllArgsConstructor;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivestreams.Publisher;

@AllArgsConstructor
public class KafkaDomainEventBus implements DomainEventBus {
    private final ReactiveMessageSender sender;

    @Override
    public <T> Publisher<Void> emit(DomainEvent<T> event) {
        return sender.send(event);
    }

    @Override
    public Publisher<Void> emitCloudEvent(CloudEvent event) {
        return sender.send(event);
    }
}
