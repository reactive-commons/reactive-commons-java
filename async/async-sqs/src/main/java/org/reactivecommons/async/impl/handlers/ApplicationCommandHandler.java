package org.reactivecommons.async.impl.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.java.Log;
import lombok.extern.log4j.Log4j2;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.impl.model.MessageSQS;
import org.reactivecommons.async.impl.model.SNSEventModel;
import reactor.core.publisher.Mono;

@Log4j2
public class ApplicationCommandHandler extends GenericMessageHandler {

  private final MessageConverter messageConverter;

  public ApplicationCommandHandler(HandlerResolver handlers, MessageConverter messageConverter) {
    super(handlers);
    this.messageConverter = messageConverter;
  }

  private Mono<RegisteredCommandHandler> getHandler(SNSEventModel msj) {
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      Command command = objectMapper.readValue(msj.getMessage(), Command.class);
      String commandName = command.getName();
      RegisteredCommandHandler handler = handlers.getCommandHandler(commandName);
      if (handler != null) {
        return Mono.just(handler);
      } else {
        log.error("Handler doesn't found for command " + commandName);
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
          Command<Object> command = messageConverter.readCommand(message, dataClass);
          return handler.getHandler()
              .handle(command);
        });
  }

}
