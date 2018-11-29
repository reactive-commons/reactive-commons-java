package org.reactivecommons.async.api.handlers.registered;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.handlers.QueryHandler;

@RequiredArgsConstructor
@Getter
public class RegisteredQueryHandler<T, R> {
    private final String path;
    private final QueryHandler<T, R> handler;
    private final Class<R> queryClass;
}
