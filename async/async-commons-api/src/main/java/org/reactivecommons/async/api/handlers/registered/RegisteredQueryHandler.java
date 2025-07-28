package org.reactivecommons.async.api.handlers.registered;

import org.reactivecommons.async.api.handlers.QueryHandlerDelegate;

public record RegisteredQueryHandler<T, C>(String path, QueryHandlerDelegate<T, C> handler, Class<C> queryClass) {
}
