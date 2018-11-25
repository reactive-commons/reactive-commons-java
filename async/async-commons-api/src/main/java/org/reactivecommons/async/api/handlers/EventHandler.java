package org.reactivecommons.async.api.handlers;

import org.reactivecommons.api.domain.DomainEvent;

public interface EventHandler<T> extends GenericHandler<Void, DomainEvent<T>> {
}
