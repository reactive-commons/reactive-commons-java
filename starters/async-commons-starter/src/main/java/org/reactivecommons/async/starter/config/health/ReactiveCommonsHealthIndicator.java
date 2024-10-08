package org.reactivecommons.async.starter.config.health;

import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.springframework.boot.actuate.health.AbstractReactiveHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Log4j2
@AllArgsConstructor
public class ReactiveCommonsHealthIndicator extends AbstractReactiveHealthIndicator {
    private final ConnectionManager manager;

    @Override
    @SuppressWarnings("unchecked")
    protected Mono<Health> doHealthCheck(Health.Builder builder) {
        return Flux.fromIterable(manager.getProviders().values())
                .flatMap(BrokerProvider::healthCheck)
                .reduceWith(Health::up, (health, status) -> reduceHealth((Health.Builder) health, (Health) status))
                .map(b -> ((Health.Builder) b).build());

    }

    private Health.Builder reduceHealth(Health.Builder builder, Health status) {
        String domain = status.getDetails().get("domain").toString();
        if (!status.getStatus().equals(Status.DOWN)) {
            log.error("Broker of domain {} is down", domain);
            return builder.down().withDetail(domain, status.getDetails());
        }
        return builder.withDetail(domain, status.getDetails());
    }
}
