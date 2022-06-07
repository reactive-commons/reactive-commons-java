package org.reactivecommons.async.impl;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.reactivecommons.async.api.DynamicRegistry;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.logging.Handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class DynamicRegistryImpTest {
  @Mock
  private Handlers resolver;
  @Mock
  private EventHandler<String> eventHandler;

  @Test
  public void shouldAddEventListener() {
    // Arrange
    DynamicRegistry registry = new DynamicRegistryImp(resolver);
    String eventName = "eventName";
    Class<String> cla = String.class;
    // Act
    Mono<Void> result = registry.listenEvent(eventName, eventHandler, cla);
    // Assert
    StepVerifier.create(result)
        .verifyComplete();
    verify(resolver, times(1)).addEventListener(any(RegisteredEventListener.class));
  }
}
