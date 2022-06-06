package org.reactivecommons.async.impl.handlers;

import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import lombok.extern.log4j.Log4j2;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.model.SNSEventModel;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public abstract class GenericMessageHandler {
  protected final HandlerResolver handlers;
  public abstract Mono handle(SNSEventModel msj);
}


