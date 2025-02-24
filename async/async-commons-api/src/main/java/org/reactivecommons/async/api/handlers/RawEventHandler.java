package org.reactivecommons.async.api.handlers;

import org.reactivecommons.api.domain.RawMessage;

public interface RawEventHandler<T extends RawMessage> extends EventHandler<T> {
}
