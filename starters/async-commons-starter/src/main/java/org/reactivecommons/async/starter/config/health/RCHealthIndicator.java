package org.reactivecommons.async.starter.config.health;

import reactor.core.publisher.Mono;

public abstract class RCHealthIndicator {

    public Mono<RCHealth> health() {
        return doHealthCheck(RCHealth.builder())
                .onErrorResume(e ->
                        Mono.just(RCHealth.builder().down().withDetail("error", e.getMessage()).build())
                );
    }

    public abstract Mono<RCHealth> doHealthCheck(RCHealth.RCHealthBuilder builder);
}
