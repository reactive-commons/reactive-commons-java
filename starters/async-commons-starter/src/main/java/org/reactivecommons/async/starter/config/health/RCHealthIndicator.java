package org.reactivecommons.async.starter.config.health;

import reactor.core.publisher.Mono;

public abstract class RCHealthIndicator {

    public Mono<RCHealth> health() {
        return doHealthCheck(RCHealth.builder());
    }

    public abstract Mono<RCHealth> doHealthCheck(RCHealth.RCHealthBuilder builder);
}
