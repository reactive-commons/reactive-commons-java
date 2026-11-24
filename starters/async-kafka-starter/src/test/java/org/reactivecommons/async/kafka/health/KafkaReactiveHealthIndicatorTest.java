package org.reactivecommons.async.kafka.health;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.kafka.listeners.GenericMessageListener;
import org.reactivecommons.async.starter.config.health.RCHealth;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        clusterAnswers("cluster123");
        // Act
        Mono<RCHealth> result = indicator.doHealthCheck(RCHealth.builder());
        // Assert
        StepVerifier.create(result)
                .assertNext(health -> {
                    assertEquals(DEFAULT_DOMAIN, health.details().get("domain"));
                    assertEquals("cluster123", health.details().get("version"));
                    assertEquals(RCHealth.Status.UP, health.status());
                })
                .verifyComplete();
    }

    /**
     * KC-13: the cluster is asked with a timeout of its own, not the 60 s of {@code default.api.timeout.ms}.
     */
    @Test
    void shouldAskTheClusterWithTheConfiguredTimeout() {
        indicator = new KafkaReactiveHealthIndicator(DEFAULT_DOMAIN, adminClient, Duration.ofMillis(1500));
        clusterAnswers("cluster123");

        StepVerifier.create(indicator.health()).expectNextCount(1).verifyComplete();

        ArgumentCaptor<DescribeClusterOptions> options = ArgumentCaptor.forClass(DescribeClusterOptions.class);
        verify(adminClient).describeCluster(options.capture());
        assertThat(options.getValue().timeoutMs()).isEqualTo(1500);
    }

    /**
     * KC-13: nothing is asked to the cluster until the health is subscribed to.
     */
    @Test
    void nothingRunsWhenTheHealthIsAssembled() {
        indicator.health();

        verifyNoInteractions(adminClient);
    }

    @Test
    void shouldBeDownWithTheErrorInItsDetails() {
        // Arrange
        when(adminClient.describeCluster(any(DescribeClusterOptions.class))).thenReturn(describeClusterResult);
        KafkaFutureImpl<String> future = new KafkaFutureImpl<>();
        future.completeExceptionally(new RuntimeException("simulate error"));
        when(describeClusterResult.clusterId()).thenReturn(future);
        // Act
        Mono<RCHealth> result = indicator.doHealthCheck(RCHealth.builder());
        // Assert
        StepVerifier.create(result)
                .assertNext(health -> {
                    assertEquals(DEFAULT_DOMAIN, health.details().get("domain"));
                    assertEquals(RCHealth.Status.DOWN, health.status());
                    assertThat(health.details().get("error").toString()).contains("simulate error");
                })
                .verifyComplete();
    }

    /**
     * KC-13: a listener that cannot get its consumer to work for longer than the grace period makes the domain
     * {@code DOWN}, even when the cluster answers.
     */
    @Test
    void shouldBeDownWhenAListenerKeepsRestartingBeyondTheGracePeriod() {
        clusterAnswers("cluster123");
        indicator.watch(listener(List.of("orders"), KafkaReactiveHealthIndicator.RESTART_GRACE_PERIOD.plusSeconds(1)));

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertEquals(RCHealth.Status.DOWN, health.status());
                    assertThat(health.details().get("error").toString()).contains("orders");
                    assertThat(listenerDetails(health)).singleElement()
                            .satisfies(detail -> assertThat(detail)
                                    .containsEntry("topics", List.of("orders"))
                                    .containsEntry("restarting", true)
                                    .containsEntry("restarts", 3L));
                })
                .verifyComplete();
    }

    @Test
    void shouldBeUpWhenAListenerRestartsWithinTheGracePeriod() {
        clusterAnswers("cluster123");
        indicator.watch(listener(List.of("orders"), Duration.ofSeconds(5)));

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertEquals(RCHealth.Status.UP, health.status());
                    assertThat(listenerDetails(health)).singleElement()
                            .satisfies(detail -> assertThat(detail).containsEntry("restartingSeconds", 5L));
                })
                .verifyComplete();
    }

    private void clusterAnswers(String clusterId) {
        when(adminClient.describeCluster(any(DescribeClusterOptions.class))).thenReturn(describeClusterResult);
        when(describeClusterResult.clusterId()).thenReturn(KafkaFuture.completedFuture(clusterId));
    }

    private static GenericMessageListener listener(List<String> topics, Duration restartingFor) {
        GenericMessageListener listener = mock(GenericMessageListener.class);
        when(listener.topics()).thenReturn(topics);
        when(listener.restartingFor()).thenReturn(restartingFor);
        when(listener.isRestarting()).thenReturn(true);
        when(listener.restarts()).thenReturn(3L);
        return listener;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listenerDetails(RCHealth health) {
        return (List<Map<String, Object>>) health.details().get("listeners");
    }
}
