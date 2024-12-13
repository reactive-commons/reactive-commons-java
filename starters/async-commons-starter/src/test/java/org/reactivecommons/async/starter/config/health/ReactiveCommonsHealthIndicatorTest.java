package org.reactivecommons.async.starter.config.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.springframework.boot.actuate.health.Health;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.when;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;
import static org.reactivecommons.async.starter.config.health.ReactiveCommonsHealthIndicator.DOMAIN;
import static org.reactivecommons.async.starter.config.health.ReactiveCommonsHealthIndicator.VERSION;

@ExtendWith(MockitoExtension.class)
class ReactiveCommonsHealthIndicatorTest {
    public static final String OTHER = "other";
    @Mock
    private BrokerProvider<?> brokerProvider;
    @Mock
    private BrokerProvider<?> brokerProvider2;
    private ReactiveCommonsHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        ConnectionManager connectionManager = new ConnectionManager();
        connectionManager.addDomain(DEFAULT_DOMAIN, brokerProvider);
        connectionManager.addDomain(OTHER, brokerProvider2);
        ReactiveCommonsHealthConfig healthConfig = new ReactiveCommonsHealthConfig();
        healthIndicator = healthConfig.reactiveCommonsHealthIndicator(connectionManager);
    }

    @Test
    void shouldBeUp() {
        // Arrange
        when(brokerProvider.healthCheck()).thenReturn(Mono.just(RCHealth.builder().up()
                .withDetail(DOMAIN, DEFAULT_DOMAIN)
                .withDetail(VERSION, "123")
                .build()));
        when(brokerProvider2.healthCheck()).thenReturn(Mono.just(RCHealth.builder().up()
                .withDetail(DOMAIN, OTHER)
                .withDetail(VERSION, "1234")
                .build()));
        // Act
        Mono<Health> flow = healthIndicator.health();
        // Assert
        StepVerifier.create(flow)
                .expectNextMatches(health -> health.getStatus().toString().equals("UP"))
                .verifyComplete();
    }

    @Test
    void shouldBeDown() {
        // Arrange
        when(brokerProvider.healthCheck()).thenReturn(Mono.just(RCHealth.builder().down()
                .withDetail(DOMAIN, DEFAULT_DOMAIN)
                .withDetail(VERSION, "123")
                .build()));
        when(brokerProvider2.healthCheck()).thenReturn(Mono.just(RCHealth.builder().up()
                .withDetail(DOMAIN, OTHER)
                .withDetail(VERSION, "1234")
                .build()));
        // Act
        Mono<Health> flow = healthIndicator.health();
        // Assert
        StepVerifier.create(flow)
                .expectNextMatches(health -> health.getStatus().toString().equals("DOWN"))
                .verifyComplete();
    }
}
