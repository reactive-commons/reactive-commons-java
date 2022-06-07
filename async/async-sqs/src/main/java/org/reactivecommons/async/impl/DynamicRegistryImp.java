package org.reactivecommons.async.impl;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.DynamicRegistry;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.api.handlers.QueryHandlerDelegate;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.commons.HandlerResolver;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class DynamicRegistryImp implements DynamicRegistry {

  private final Handlers resolver;

  @Override
  public <T> Mono<Void> listenEvent(String eventName, EventHandler<T> fn, Class<T> eventClass) {
    resolver.addEventListener(new RegisteredEventListener<>(eventName, fn, eventClass));
    return Mono.empty();
  }

  @Override
  public <T, R> void serveQuery(String resource, QueryHandler<T, R> handler, Class<R> queryClass) {

  }

  @Override
  public <R> void serveQuery(String resource, QueryHandlerDelegate<Void, R> handler, Class<R> queryClass) {

  }

  @Override
  public Mono<Void> startListeningEvent(String eventName) {
    return null;
  }

  @Override
  public Mono<Void> stopListeningEvent(String eventName) {
    return null;
  }
}
