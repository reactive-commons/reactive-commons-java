package org.reactivecommons.async.api.handlers;

import org.reactivecommons.api.domain.RawMessage;

public interface RawCommandHandler<T extends RawMessage> extends CommandHandler<T> {
}
