package org.reactivecommons.async.starter.config.health;

import reactor.core.publisher.Mono;

public abstract class RCHealthIndicator {

    public Mono<RCHealth> health() {
        var builder = RCHealth.builder();
        return doHealthCheck(builder)
                .onErrorResume(e ->
                        Mono.just(builder
                                .down()
                                .withDetail("error", e.getMessage())
                                .build())
                );
    }

    public abstract Mono<RCHealth> doHealthCheck(RCHealth.RCHealthBuilder builder);
}
