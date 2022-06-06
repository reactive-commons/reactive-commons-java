package org.reactivecommons.async.impl;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.impl.sns.communications.Sender;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class SNSDomainEventBus implements DomainEventBus {

  private final Sender sender;
  public final  String topicName ;

  @Override
  public <T> Mono<Void> emit(DomainEvent<T> event) {
    return sender.publish(event, topicName)
        .onErrorMap(err -> new RuntimeException("Event send failure: " + event.getName() + " Reason: "+ err.getMessage(), err));
  }

}
