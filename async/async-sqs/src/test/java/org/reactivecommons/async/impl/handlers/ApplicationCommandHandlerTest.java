package org.reactivecommons.async.impl.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.reactivecommons.async.api.handlers.CommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.impl.converters.JacksonMessageConverter;
import org.reactivecommons.async.impl.model.SNSEventModel;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class ApplicationCommandHandlerTest {
  @Mock
  private RegisteredCommandHandler commandHandler;
  @Mock
  private CommandHandler<String> handler;

  private HandlerResolver resolver;
  private MessageConverter messageConverter;

  @BeforeAll
  public void setup() {
    Map<String, RegisteredCommandHandler<?>> commandHandlers = new ConcurrentHashMap<>();
    Map<String, RegisteredEventListener<?>> eventListeners = new ConcurrentHashMap<>();
    eventListeners.put("event.name", new RegisteredEventListener<>("event.name", message -> Mono.empty(), String.class));
    eventListeners.put("event.name2", new RegisteredEventListener<>("event.name2", message -> Mono.empty(), String.class));
    eventListeners.put("some.*", new RegisteredEventListener<>("some.*", message -> Mono.empty(), String.class));
    Map<String, RegisteredEventListener<?>> eventsToBind = new ConcurrentHashMap<>();
    eventsToBind.put("event.name", new RegisteredEventListener<>("event.name", message -> Mono.empty(), String.class));
    eventsToBind.put("event.name2", new RegisteredEventListener<>("event.name2", message -> Mono.empty(), String.class));
    Map<String, RegisteredEventListener<?>> notificationEventListeners = new ConcurrentHashMap<>();
    Map<String, RegisteredQueryHandler<?, ?>> queryHandlers = new ConcurrentHashMap<>();
    resolver = new HandlerResolver(queryHandlers, eventListeners, eventsToBind, notificationEventListeners, commandHandlers);
    messageConverter = new JacksonMessageConverter(new ObjectMapper());
  }

  @Test
  public void shouldGetHandlerCorrectly() {
    // Arrange
    when(handler.handle(any())).thenReturn(Mono.empty());
    when(commandHandler.getInputClass()).thenReturn(String.class);
    when(commandHandler.getHandler()).thenReturn(handler);
    ApplicationCommandHandler commandHandler = new ApplicationCommandHandler(resolver, messageConverter);
    SNSEventModel eventModel = new SNSEventModel();
    eventModel.setMessage("{\"name\":\"my.command\",\"commandId\":\"my.command.id\",\"data\":\"string data\"}");
    // Act
    Mono<Void> handledMessage = commandHandler.handle(eventModel);
    // Assert
    StepVerifier.create(handledMessage)
        .expectComplete()
        .verify();
  }

  @Test
  public void shouldReturnEmptyWhenNoHandler() {
    // Arrange
    ApplicationCommandHandler commandHandler = new ApplicationCommandHandler(resolver, messageConverter);
    SNSEventModel eventModel = new SNSEventModel();
    eventModel.setMessage("{\"name\":\"non-existent\",\"commandId\":\"my.command.id\",\"data\":\"string data\"}");
    // Act
    Mono<Void> handledMessage = commandHandler.handle(eventModel);
    // Assert
    StepVerifier.create(handledMessage)
        .expectComplete()
        .verify();
  }

  @Test
  public void shouldHandleErrorParsingJson() {
    // Arrange
    ApplicationCommandHandler commandHandler = new ApplicationCommandHandler(resolver, messageConverter);
    SNSEventModel eventModel = new SNSEventModel();
    eventModel.setMessage("it's a bad command json");
    // Act
    Mono<Void> handledMessage = commandHandler.handle(eventModel);
    // Assert
    StepVerifier.create(handledMessage)
        .expectError(JsonProcessingException.class)
        .verify();
  }

}
