package org.reactivecommons.async.api.handlers;

import reactor.core.publisher.Mono;

/**
 * Interface for setting up topology handlers.
 */
@FunctionalInterface
public interface TopologyHandlerSetup {
    Mono<Void> setup(Object topologyCreator);
}
