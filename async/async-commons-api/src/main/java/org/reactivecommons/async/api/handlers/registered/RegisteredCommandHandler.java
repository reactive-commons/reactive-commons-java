package org.reactivecommons.async.api.handlers.registered;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.handlers.CommandHandler;

@RequiredArgsConstructor
@Getter
public class RegisteredCommandHandler<T> {
    private final String path;
    private final CommandHandler<T> handler;
    private final Class<T> inputClass;
}
