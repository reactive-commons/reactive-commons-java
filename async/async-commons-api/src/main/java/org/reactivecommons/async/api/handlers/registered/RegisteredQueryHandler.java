package org.reactivecommons.async.api.handlers.registered;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public class RegisteredQueryHandler<R> {
    private final String path;
    private final Object handler;
    private final Class<R> queryClass;
}
