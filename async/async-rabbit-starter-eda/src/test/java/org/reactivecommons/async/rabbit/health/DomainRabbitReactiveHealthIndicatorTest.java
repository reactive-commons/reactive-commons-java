package org.reactivecommons.async.rabbit.health;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
import org.reactivecommons.async.rabbit.config.ConnectionManager;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Health.Builder;
import org.springframework.boot.actuate.health.Status;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@ExtendWith(MockitoExtension.class)
public class DomainRabbitReactiveHealthIndicatorTest {
    @Mock
    private ConnectionFactoryProvider provider;
    @Mock
    private ConnectionFactory factory;
    @Mock
    private Connection connection;

    private DomainRabbitReactiveHealthIndicator indicator;

    @BeforeEach
    void setup() {
        ConnectionManager connectionManager = new ConnectionManager();
        connectionManager.addDomain(DEFAULT_DOMAIN, null, null, provider);
        connectionManager.addDomain("domain2", null, null, provider);
        connectionManager.addDomain("domain3", null, null, provider);
        indicator = new DomainRabbitReactiveHealthIndicator(connectionManager);
        when(provider.getConnectionFactory()).thenReturn(factory);
    }

    @Test
    void shouldBeUp() throws IOException, TimeoutException {
        // Arrange
        Map<String, Object> properties = new TreeMap<>();
        properties.put("version", "1.2.3");
        when(factory.newConnection()).thenReturn(connection);
        when(connection.getServerProperties()).thenReturn(properties);
        // Act
        Mono<Health> result = indicator.doHealthCheck(new Builder());
        // Assert
        StepVerifier.create(result)
                .assertNext(health -> {
                    assertEquals("1.2.3", health.getDetails().get(DEFAULT_DOMAIN));
                    assertEquals("1.2.3", health.getDetails().get("domain2"));
                    assertEquals("1.2.3", health.getDetails().get("domain3"));
                    assertEquals(Status.UP, health.getStatus());
                })
                .verifyComplete();
    }

    @Test
    void shouldBeDown() throws IOException, TimeoutException {
        // Arrange
        when(factory.newConnection()).thenThrow(new TimeoutException("Connection timeout"));
        // Act
        Mono<Health> result = indicator.doHealthCheck(new Builder());
        // Assert
        StepVerifier.create(result)
                .expectError(TimeoutException.class)
                .verify();
    }
}
