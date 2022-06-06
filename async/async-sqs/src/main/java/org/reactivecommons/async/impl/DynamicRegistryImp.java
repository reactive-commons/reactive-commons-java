package org.reactivecommons.async.impl;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.DynamicRegistry;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class DynamicRegistryImp implements DynamicRegistry {

  private final HandlerResolver resolver;

  @Override
  public <T> Mono<Void> listenEvent(String eventName, EventHandler<T> fn, Class<T> eventClass) {
    resolver.addEventListener(new RegisteredEventListener<>(eventName, fn, eventClass));
    return Mono.empty();
  }
}
