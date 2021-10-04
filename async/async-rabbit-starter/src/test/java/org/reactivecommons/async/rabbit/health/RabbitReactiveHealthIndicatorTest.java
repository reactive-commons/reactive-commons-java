package org.reactivecommons.async.rabbit.health;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
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

@ExtendWith(MockitoExtension.class)
public class RabbitReactiveHealthIndicatorTest {
    @Mock
    private ConnectionFactoryProvider provider;
    @Mock
    private ConnectionFactory factory;
    @Mock
    private Connection connection;
    @InjectMocks
    private RabbitReactiveHealthIndicator indicator;

    @BeforeEach
    void setup() {
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
                    assertEquals("1.2.3", health.getDetails().get("version"));
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
