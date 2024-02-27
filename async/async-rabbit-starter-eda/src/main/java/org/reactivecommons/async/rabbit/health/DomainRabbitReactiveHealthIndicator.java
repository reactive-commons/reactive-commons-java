package org.reactivecommons.async.rabbit.health;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
import org.reactivecommons.async.rabbit.config.ConnectionManager;
import org.springframework.boot.actuate.health.AbstractReactiveHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

@RequiredArgsConstructor
public class DomainRabbitReactiveHealthIndicator extends AbstractReactiveHealthIndicator {
    private static final String VERSION = "version";
    private final ConnectionManager manager;

    @Override
    protected Mono<Health> doHealthCheck(Health.Builder builder) {
        return Mono.zip(manager.getProviders()
                .entrySet()
                .stream()
                .map(entry -> checkSingle(entry.getKey(), entry.getValue().getProvider()))
                .collect(Collectors.toList()), this::merge);
    }

    private Health merge(Object[] results) {
        Health.Builder builder = Health.up();
        for (Object obj : results) {
            Status status = (Status) obj;
            builder.withDetail(status.getDomain(), status.getVersion());
        }
        return builder.build();
    }

    private Mono<Status> checkSingle(String domain, ConnectionFactoryProvider provider) {
        return Mono.defer(() -> getVersion(provider))
                .map(version -> Status.builder().version(version).domain(domain).build());
    }

    private Mono<String> getVersion(ConnectionFactoryProvider provider) {
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
