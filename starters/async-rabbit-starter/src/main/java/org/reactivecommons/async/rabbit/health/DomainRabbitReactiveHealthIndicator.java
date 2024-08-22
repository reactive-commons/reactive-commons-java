package org.reactivecommons.async.rabbit.health;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.reactivecommons.async.rabbit.config.ConnectionManager;
import org.springframework.boot.actuate.health.AbstractReactiveHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import reactor.core.publisher.Mono;

import java.net.SocketException;
import java.util.Map;
import java.util.stream.Collectors;

@Log4j2
public class DomainRabbitReactiveHealthIndicator extends AbstractReactiveHealthIndicator {
    private static final String VERSION = "version";
    private final Map<String, ConnectionFactory> domainProviders;

    public DomainRabbitReactiveHealthIndicator(ConnectionManager manager) {
        this.domainProviders = manager.getProviders().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    ConnectionFactory connection = entry.getValue().getProvider().getConnectionFactory().clone();
                    connection.useBlockingIo();
                    return connection;
                }));
    }

    @Override
    protected Mono<Health> doHealthCheck(Health.Builder builder) {
        return Mono.zip(domainProviders.entrySet().stream()
                .map(entry -> checkSingle(entry.getKey(), entry.getValue()))
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

    private Mono<Status> checkSingle(String domain, ConnectionFactory connectionFactory) {
        return Mono.defer(() -> Mono.just(getRawVersion(connectionFactory)))
                .map(version -> Status.builder().version(version).domain(domain).build());
    }

    @SneakyThrows
    private String getRawVersion(ConnectionFactory factory) {
        Connection connection = null;
        try {
            connection = factory.newConnection();
            return connection.getServerProperties().get(VERSION).toString();
        } catch (SocketException e) {
            log.warn("Identified error", e);
            throw new RuntimeException(e);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception e) {
                    log.error("Error closing health connection", e);
                }
            }
        }
    }
}
