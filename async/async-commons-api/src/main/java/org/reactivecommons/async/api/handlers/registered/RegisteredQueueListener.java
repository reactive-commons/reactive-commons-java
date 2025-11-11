package org.reactivecommons.async.api.handlers.registered;

import org.reactivecommons.api.domain.RawMessage;
import org.reactivecommons.async.api.handlers.RawEventHandler;
import org.reactivecommons.async.api.handlers.TopologyHandlerSetup;


public record RegisteredQueueListener(String queueName,
                                      RawEventHandler<RawMessage> handler,
                                      TopologyHandlerSetup topologyHandlerSetup) {
}
