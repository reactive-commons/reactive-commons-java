package org.reactivecommons.async.starter.config.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.springframework.boot.health.contributor.AbstractReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Log4j2
@RequiredArgsConstructor
public class ReactiveCommonsHealthIndicator extends AbstractReactiveHealthIndicator {
    public static final String DOMAIN = "domain";
    public static final String VERSION = "version";
    private final ConnectionManager manager;

    @Override
    @SuppressWarnings("unchecked")
    protected Mono<Health> doHealthCheck(Health.Builder builder) {
        return Flux.fromIterable(manager.getProviders().values())
                .flatMap(BrokerProvider::healthCheck)
                .reduceWith(Health::up, (health, status) ->
                        reduceHealth((Health.Builder) health, (RCHealth) status)
                )
                .map(b -> ((Health.Builder) b).build());

    }

    private Health.Builder reduceHealth(Health.Builder builder, RCHealth health) {
        String domain = health.details().get(DOMAIN).toString();
        if (health.status().equals(RCHealth.Status.DOWN)) {
            log.error("Broker of domain {} is down", domain);
            return builder.down().withDetail(domain, health.details());
        }
        return builder.withDetail(domain, health.details());
    }
}
