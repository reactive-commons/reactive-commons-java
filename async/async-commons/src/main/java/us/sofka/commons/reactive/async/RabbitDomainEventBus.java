package us.sofka.commons.reactive.async;

import reactor.core.publisher.Mono;
import us.sofka.commons.reactive.async.api.DomainEvent;
import us.sofka.commons.reactive.async.api.DomainEventBus;

import java.util.Collections;

public class RabbitDomainEventBus implements DomainEventBus {

    private final ReactiveMessageSender sender;

    public RabbitDomainEventBus(ReactiveMessageSender sender) {
        this.sender = sender;
    }

    @Override
    public <T> Mono<Void> emit(DomainEvent<T> event) {
        return sender.sendWithConfirm(event, event.getName(), Collections.emptyMap())
            .onErrorMap(err -> new RuntimeException("Event send faulure: " + event.getName(), err));
    }

}
