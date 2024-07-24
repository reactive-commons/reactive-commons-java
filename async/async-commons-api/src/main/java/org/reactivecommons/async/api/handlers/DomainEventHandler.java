package org.reactivecommons.async.api.handlers;

import org.reactivecommons.api.domain.DomainEvent;

public interface DomainEventHandler<T> extends EventHandler<DomainEvent<T>>{
}
