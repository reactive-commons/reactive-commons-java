package org.reactivecommons.async.impl.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.java.Log;
import lombok.extern.log4j.Log4j2;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.impl.model.MessageSQS;
import org.reactivecommons.async.impl.model.SNSEventModel;
import reactor.core.publisher.Mono;

@Log4j2
public class ApplicationEventHandler extends GenericMessageHandler {

  private final MessageConverter messageConverter;

  public ApplicationEventHandler(HandlerResolver handlers, MessageConverter messageConverter) {
    super(handlers);
    this.messageConverter = messageConverter;
  }

  private Mono<RegisteredEventListener> getHandler(SNSEventModel msj) {
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      DomainEvent event = objectMapper.readValue(msj.getMessage(), DomainEvent.class);
      String eventName = event.getName();
      RegisteredEventListener handler = handlers.getEventListener(eventName);
      if (handler != null) {
        return Mono.just(handler);
      } else {
        log.info("Handler doesn't found for event " + eventName);
        return Mono.empty();
      }
    } catch (JsonProcessingException e) {
      return Mono.error(e);
    }
  }

  public Mono<Void> handle(SNSEventModel msj) {
    return getHandler(msj)
        .flatMap(handler -> {
          Class dataClass = handler.getInputClass();
          MessageSQS message = new MessageSQS(msj.getMessage());
          DomainEvent<Object> domainEvent = messageConverter.readDomainEvent(message, dataClass);
          return handler.getHandler()
              .handle(domainEvent);
        });
  }

}
