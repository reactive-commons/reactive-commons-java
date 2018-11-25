package org.reactivecommons.async.api.handlers;

import org.reactivecommons.api.domain.Command;

public interface CommandHandler<T> extends GenericHandler<Void, Command<T>> {
}
