package org.reactivecommons.async.api.handlers.registered;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.handlers.QueryHandlerDelegate;

@RequiredArgsConstructor
@Getter
public class RegisteredQueryHandler<T, C> {
    private final String path;
    private final QueryHandlerDelegate<T, C> handler;
    private final Class<C> queryClass;
}
