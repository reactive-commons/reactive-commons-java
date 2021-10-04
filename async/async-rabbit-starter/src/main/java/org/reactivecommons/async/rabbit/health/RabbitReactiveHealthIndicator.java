package org.reactivecommons.async.rabbit.health;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
import org.springframework.boot.actuate.health.AbstractReactiveHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class RabbitReactiveHealthIndicator extends AbstractReactiveHealthIndicator {
    private static final String VERSION = "version";
    private final ConnectionFactoryProvider provider;

    @Override
    protected Mono<Health> doHealthCheck(Health.Builder builder) {
        return Mono.defer(this::getVersion)
                .map(version -> builder.up().withDetail(VERSION, version).build());
    }

    private Mono<String> getVersion() {
        return Mono.just(provider)
                .map(ConnectionFactoryProvider::getConnectionFactory)
                .map(this::getRawVersion);
    }

    @SneakyThrows
    private String getRawVersion(ConnectionFactory factory) {
        try (Connection connection = factory.newConnection()) {
            return connection.getServerProperties().get(VERSION).toString();
        }
    }
}
