package org.reactivecommons.async.kafka.health;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.starter.config.health.RCHealth;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

/**
 * KC-13 (P11): with the broker unreachable the health check answers {@code DOWN} within its timeout, instead of
 * waiting the 60 s of {@code default.api.timeout.ms}, far below the 10 s after which Spring Boot reports a slow health
 * indicator and the timeouts of the probes of Kubernetes.
 */
class KafkaReactiveHealthIndicatorUnreachableTest {

    private final AdminClient adminClient = AdminClient.create(Map.of(
            // Nothing listens on port 1: every connection is refused
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
            AdminClientConfig.CLIENT_ID_CONFIG, "health-unreachable-test",
            AdminClientConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, "1000"));

    @AfterEach
    void tearDown() {
        adminClient.close(Duration.ZERO);
    }

    @Test
    void answersDownWithinTheTimeoutWhenTheBrokerIsUnreachable() {
        KafkaReactiveHealthIndicator indicator = new KafkaReactiveHealthIndicator(DEFAULT_DOMAIN, adminClient);
        Duration timeout = KafkaReactiveHealthIndicator.DEFAULT_TIMEOUT;

        Duration took = StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.status()).isEqualTo(RCHealth.Status.DOWN);
                    assertThat(health.details()).containsEntry("domain", DEFAULT_DOMAIN).containsKey("error");
                    assertThat(health.details().get("error").toString()).isNotBlank();
                })
                .expectComplete()
                .verify(timeout.plusSeconds(1));

        assertThat(took).isLessThan(timeout.plusSeconds(1));
    }
}
