package org.reactivecommons.async.rabbit.health;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.reactivecommons.async.starter.config.health.RCHealthIndicator;
import reactor.core.publisher.Mono;

import java.net.SocketException;

import static org.reactivecommons.async.starter.config.health.ReactiveCommonsHealthIndicator.DOMAIN;
import static org.reactivecommons.async.starter.config.health.ReactiveCommonsHealthIndicator.VERSION;

@Log4j2
public class RabbitReactiveHealthIndicator extends RCHealthIndicator {
    private final String domain;
    private final ConnectionFactory connectionFactory;

    public RabbitReactiveHealthIndicator(String domain, ConnectionFactory connectionFactory) {
        this.domain = domain;
        this.connectionFactory = connectionFactory.clone();
        this.connectionFactory.useBlockingIo();
    }

    @Override
    public Mono<RCHealth> doHealthCheck(RCHealth.RCHealthBuilder builder) {
        builder.withDetail(DOMAIN, domain);
        return Mono.fromCallable(() -> getRawVersion(connectionFactory))
                .map(status -> builder.up().withDetail(VERSION, status).build());
    }

    @SneakyThrows
    private String getRawVersion(ConnectionFactory factory) {
        Connection connection = null;
        try {
            connection = factory.newConnection();
            return connection.getServerProperties().get(VERSION).toString();
        } catch (SocketException e) {
            log.warn("Identified error", e);
            throw new RabbitMQHealthException(e);
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
