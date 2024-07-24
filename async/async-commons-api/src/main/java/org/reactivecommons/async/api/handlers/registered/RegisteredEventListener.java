package org.reactivecommons.async.api.handlers.registered;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.handlers.EventHandler;

@RequiredArgsConstructor
@Getter
public class RegisteredEventListener<T, D> {
    private final String path;
    private final EventHandler<D> handler;
    private final Class<T> inputClass;
}
