package org.reactivecommons.async.api.handlers.registered;

import org.reactivecommons.async.api.handlers.CommandHandler;

public record RegisteredCommandHandler<T, D>(String path, CommandHandler<D> handler, Class<T> inputClass) {
}
