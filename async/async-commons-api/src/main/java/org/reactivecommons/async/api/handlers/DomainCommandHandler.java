package org.reactivecommons.async.api.handlers;

import org.reactivecommons.api.domain.Command;

public interface DomainCommandHandler<T> extends CommandHandler<Command<T>> {
}
