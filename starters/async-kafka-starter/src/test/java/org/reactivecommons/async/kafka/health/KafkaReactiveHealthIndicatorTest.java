package org.reactivecommons.async.kafka.health;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Health.Builder;
import org.springframework.boot.actuate.health.Status;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@ExtendWith(MockitoExtension.class)
class KafkaReactiveHealthIndicatorTest {
    @Mock
    private AdminClient adminClient;
    @Mock
    private DescribeClusterResult describeClusterResult;

    private KafkaReactiveHealthIndicator indicator;

    @BeforeEach
    void setup() {
        indicator = new KafkaReactiveHealthIndicator(DEFAULT_DOMAIN, adminClient);
    }

    @Test
    void shouldBeUp() {
        // Arrange
        when(adminClient.describeCluster()).thenReturn(describeClusterResult);
        when(describeClusterResult.clusterId()).thenReturn(KafkaFuture.completedFuture("cluster123"));
        // Act
        Mono<Health> result = indicator.doHealthCheck(new Builder());
        // Assert
        StepVerifier.create(result)
                .assertNext(health -> {
                    assertEquals(DEFAULT_DOMAIN, health.getDetails().get("domain"));
                    assertEquals("cluster123", health.getDetails().get("version"));
                    assertEquals(Status.UP, health.getStatus());
                })
                .verifyComplete();
    }

    @Test
    void shouldBeDown() {
        // Arrange
        when(adminClient.describeCluster()).thenReturn(describeClusterResult);
        KafkaFutureImpl<String> future = new KafkaFutureImpl<>();
        future.completeExceptionally(new RuntimeException("simulate error"));
        when(describeClusterResult.clusterId()).thenReturn(future);
        // Act
        Mono<Health> result = indicator.doHealthCheck(new Builder());
        // Assert
        StepVerifier.create(result)
                .expectNextMatches(health -> {
                    assertEquals(DEFAULT_DOMAIN, health.getDetails().get("domain"));
                    assertEquals(Status.DOWN, health.getStatus());
                    return true;
                })
                .verifyComplete();
    }
}
