package org.reactivecommons.async.kafka.health;

import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.actuate.health.AbstractReactiveHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import reactor.core.publisher.Mono;

@Log4j2
@AllArgsConstructor
public class KafkaReactiveHealthIndicator extends AbstractReactiveHealthIndicator {
    public static final String VERSION = "version";
    private final String domain;
    private final AdminClient adminClient;

    @Override
    protected Mono<Health> doHealthCheck(Health.Builder builder) {
        builder.withDetail("domain", domain);
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
