package org.reactivecommons.async.kafka.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.kafka.clients.admin.AdminClient;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.reactivecommons.async.starter.config.health.RCHealthIndicator;
import reactor.core.publisher.Mono;

import static org.reactivecommons.async.starter.config.health.ReactiveCommonsHealthIndicator.DOMAIN;
import static org.reactivecommons.async.starter.config.health.ReactiveCommonsHealthIndicator.VERSION;

@Log4j2
@RequiredArgsConstructor
public class KafkaReactiveHealthIndicator extends RCHealthIndicator {
    private final String domain;
    private final AdminClient adminClient;

    @Override
    public Mono<RCHealth> doHealthCheck(RCHealth.RCHealthBuilder builder) {
        builder.withDetail(DOMAIN, domain);
        return checkKafkaHealth()
                .map(clusterId -> builder.up().withDetail(VERSION, clusterId).build())
                .onErrorReturn(builder.down().build());
    }

    private Mono<String> checkKafkaHealth() {
        return Mono.fromFuture(adminClient.describeCluster().clusterId()
                        .toCompletionStage()
                        .toCompletableFuture())
                .doOnError(e -> log.error("Error checking Kafka health in domain {}", domain, e));
    }

}
