package org.reactivecommons.async.rabbit.health;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.starter.config.health.RCHealth;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.SocketException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@ExtendWith(MockitoExtension.class)
class RabbitReactiveHealthIndicatorTest {
    @Mock
    private ConnectionFactory factory;
    @Mock
    private Connection connection;

    private RabbitReactiveHealthIndicator indicator;

    @BeforeEach
    void setup() {
        when(factory.clone()).thenReturn(factory);
        indicator = new RabbitReactiveHealthIndicator(DEFAULT_DOMAIN, factory);
    }

    @Test
    void shouldBeUp() throws IOException, TimeoutException {
        // Arrange
        Map<String, Object> properties = new TreeMap<>();
        properties.put("version", "1.2.3");
        when(factory.newConnection()).thenReturn(connection);
        when(connection.getServerProperties()).thenReturn(properties);
        // Act
        Mono<RCHealth> result = indicator.doHealthCheck(RCHealth.builder());
        // Assert
        StepVerifier.create(result)
                .assertNext(health -> {
                    assertEquals(DEFAULT_DOMAIN, health.details().get("domain"));
                    assertEquals("1.2.3", health.details().get("version"));
                    assertEquals(RCHealth.Status.UP, health.status());
                })
                .verifyComplete();
    }

    @Test
    void shouldBeUpAndIgnoreCloseError() throws IOException, TimeoutException {
        // Arrange
        Map<String, Object> properties = new TreeMap<>();
        properties.put("version", "1.2.3");
        when(factory.newConnection()).thenReturn(connection);
        when(connection.getServerProperties()).thenReturn(properties);
        doThrow(new IOException("Error closing connection")).when(connection).close();
        // Act
        Mono<RCHealth> result = indicator.doHealthCheck(RCHealth.builder());
        // Assert
        StepVerifier.create(result)
                .assertNext(health -> {
                    assertEquals(DEFAULT_DOMAIN, health.details().get("domain"));
                    assertEquals("1.2.3", health.details().get("version"));
                    assertEquals(RCHealth.Status.UP, health.status());
                })
                .verifyComplete();
    }

    @Test
    void shouldBeDown() throws IOException, TimeoutException {
        // Arrange
        when(factory.newConnection()).thenThrow(new TimeoutException("Connection timeout"));
        // Act
        Mono<RCHealth> result = indicator.doHealthCheck(RCHealth.builder());
        // Assert
        StepVerifier.create(result)
                .expectError(TimeoutException.class)
                .verify();
    }

    @Test
    void shouldBeDownWhenSocketException() throws IOException, TimeoutException {
        // Arrange
        when(factory.newConnection()).thenThrow(new SocketException("Connection timeout"));
        // Act
        Mono<RCHealth> result = indicator.doHealthCheck(RCHealth.builder());
        // Assert
        StepVerifier.create(result)
                .expectError(RuntimeException.class)
                .verify();
    }
}
