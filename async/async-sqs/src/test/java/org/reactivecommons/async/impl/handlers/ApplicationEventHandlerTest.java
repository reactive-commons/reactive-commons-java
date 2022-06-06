package org.reactivecommons.async.impl.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.impl.converters.json.JacksonMessageConverter;
import org.reactivecommons.async.impl.model.SNSEventModel;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ApplicationEventHandlerTest {
  @Mock
  private RegisteredEventListener eventListener;
  @Mock
  private EventHandler<String> handler;

  private HandlerResolver resolver;
  private MessageConverter messageConverter;

  @Before
  public void setup() {
    Map<String, RegisteredCommandHandler> commandHandlers = new ConcurrentHashMap<>();
    Map<String, RegisteredEventListener> eventListeners = new ConcurrentHashMap<>();
    Map<String, RegisteredQueryHandler> queryHandlers = new ConcurrentHashMap<>();
    eventListeners.put("my.event", eventListener);
    resolver = new HandlerResolver(queryHandlers, eventListeners, commandHandlers);
    messageConverter = new JacksonMessageConverter(new ObjectMapper());
  }

  @Test
  public void shouldGetHandlerCorrectly() {
    // Arrange
    when(handler.handle(any())).thenReturn(Mono.empty());
    when(eventListener.getInputClass()).thenReturn(String.class);
    when(eventListener.getHandler()).thenReturn(handler);
    ApplicationEventHandler eventHandler = new ApplicationEventHandler(resolver, messageConverter);
    SNSEventModel eventModel = new SNSEventModel();
    eventModel.setMessage("{\"name\":\"my.event\",\"eventId\":\"my.event.id\",\"data\":\"string data\"}");
    // Act
    Mono<Void> handledMessage = eventHandler.handle(eventModel);
    // Assert
    StepVerifier.create(handledMessage)
        .expectComplete()
        .verify();
  }

  @Test
  public void shouldReturnEmptyWhenNoHandler() {
    // Arrange
    ApplicationEventHandler eventHandler = new ApplicationEventHandler(resolver, messageConverter);
    SNSEventModel eventModel = new SNSEventModel();
    eventModel.setMessage("{\"name\":\"non-existent\",\"eventId\":\"my.event.id\",\"data\":\"string data\"}");
    // Act
    Mono<Void> handledMessage = eventHandler.handle(eventModel);
    // Assert
    StepVerifier.create(handledMessage)
        .expectComplete()
        .verify();
  }

  @Test
  public void shouldHandleErrorParsingJson() {
    // Arrange
    ApplicationEventHandler commandHandler = new ApplicationEventHandler(resolver, messageConverter);
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
