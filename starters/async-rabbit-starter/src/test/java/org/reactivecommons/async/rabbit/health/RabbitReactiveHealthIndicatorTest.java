package org.reactivecommons.async.rabbit.health;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.rabbit.communications.PublisherStatus;
import org.reactivecommons.async.starter.config.health.RCHealth;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@ExtendWith(MockitoExtension.class)
class RabbitReactiveHealthIndicatorTest {
    @Mock
    private Connection connection;

    @Test
    void shouldBeUpWhenTheApplicationConnectionIsOpen() {
        when(connection.isOpen()).thenReturn(true);
        when(connection.getServerProperties()).thenReturn(Map.of("version", "4.1.0"));
        var indicator = new RabbitReactiveHealthIndicator(DEFAULT_DOMAIN, () -> Optional.of(connection));

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.status()).isEqualTo(RCHealth.Status.UP);
                    assertThat(health.details())
                            .containsEntry("domain", DEFAULT_DOMAIN)
                            .containsEntry("version", "4.1.0");
                })
                .verifyComplete();
    }

    @Test
    void shouldReportUnknownVersionWhenTheServerDoesNotSendIt() {
        when(connection.isOpen()).thenReturn(true);
        when(connection.getServerProperties()).thenReturn(Map.of());
        var indicator = new RabbitReactiveHealthIndicator(DEFAULT_DOMAIN, () -> Optional.of(connection));

        StepVerifier.create(indicator.health())
                .assertNext(health -> assertThat(health.details()).containsEntry("version", "unknown"))
                .verifyComplete();
    }

    @Test
    void shouldBeDownWhileTheConnectionIsNotEstablished() {
        var indicator = new RabbitReactiveHealthIndicator(DEFAULT_DOMAIN, Optional::empty);

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.status()).isEqualTo(RCHealth.Status.DOWN);
                    assertThat(health.details())
                            .containsEntry("domain", DEFAULT_DOMAIN)
                            .containsEntry("error", "Connection to RabbitMQ not established yet");
                })
                .verifyComplete();
    }

    @Test
    void shouldBeDownWithTheReasonWhenTheConnectionIsClosed() {
        when(connection.isOpen()).thenReturn(false);
        when(connection.getCloseReason())
                .thenReturn(new ShutdownSignalException(true, false, null, connection));
        var indicator = new RabbitReactiveHealthIndicator(DEFAULT_DOMAIN, () -> Optional.of(connection));

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.status()).isEqualTo(RCHealth.Status.DOWN);
                    assertThat(health.details().get("error").toString())
                            .startsWith("Connection to RabbitMQ is closed: connection error");
                })
                .verifyComplete();
    }

    @Test
    void shouldOnlyReadTheConnectionStateWithoutAnyNetworkCall() {
        when(connection.isOpen()).thenReturn(true);
        when(connection.getServerProperties()).thenReturn(Map.of("version", "4.1.0"));
        var indicator = new RabbitReactiveHealthIndicator(DEFAULT_DOMAIN, () -> Optional.of(connection));

        StepVerifier.create(indicator.health()).expectNextCount(1).verifyComplete();

        verify(connection).isOpen();
        verify(connection).getServerProperties();
        verifyNoMoreInteractions(connection); // no channel, no new connection, no close
    }

    @Test
    void shouldBeDownWhenThePublishingFlowsKeepFailingWithoutAConfirmedMessage() {
        var status = new PublisherStatus(true, true, 3, Instant.now(), "boom", null, false, null, null);
        var indicator = openConnectionIndicator(status);

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.status()).isEqualTo(RCHealth.Status.DOWN);
                    assertThat(health.details().get("error").toString())
                            .contains("publishing flows keep failing")
                            .contains("boom");
                    assertThat(health.details()).containsEntry("lastFailure", status.lastConfirmFailure());
                })
                .verifyComplete();
    }

    @Test
    void shouldBeUpWhileTheNumberOfFailuresIsBelowTheThreshold() {
        var status = new PublisherStatus(true, true, 2, Instant.now(), "boom", null, false, null, null);

        assertThat(healthOf(openConnectionIndicator(status))).isEqualTo(RCHealth.Status.UP);
    }

    @Test
    void shouldBeUpWhenTheLastFailureIsStale() {
        var status = new PublisherStatus(true, true, 10, Instant.now().minusSeconds(120), "old failure", null,
                false, null, null);

        assertThat(healthOf(openConnectionIndicator(status))).isEqualTo(RCHealth.Status.UP);
    }

    @Test
    void shouldBeDownWhenNoConfirmFlowHasBeenLiveForLongerThanTheGracePeriod() {
        var status = new PublisherStatus(false, true, 0, null, null, Instant.now().minusSeconds(30),
                false, null, null);
        var indicator = openConnectionIndicator(status);

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.status()).isEqualTo(RCHealth.Status.DOWN);
                    assertThat(health.details().get("error").toString())
                            .contains("publishing flows are down since");
                })
                .verifyComplete();
    }

    @Test
    void shouldBeUpWhileTheFlowsAreBrieflyDown() {
        var status = new PublisherStatus(false, true, 0, null, null, Instant.now().minusSeconds(2),
                false, null, null);

        assertThat(healthOf(openConnectionIndicator(status))).isEqualTo(RCHealth.Status.UP);
    }

    private RabbitReactiveHealthIndicator openConnectionIndicator(PublisherStatus status) {
        when(connection.isOpen()).thenReturn(true);
        when(connection.getServerProperties()).thenReturn(Map.of("version", "4.1.0"));
        return new RabbitReactiveHealthIndicator(DEFAULT_DOMAIN, () -> Optional.of(connection), () -> status);
    }

    private RCHealth.Status healthOf(RabbitReactiveHealthIndicator indicator) {
        return indicator.health().block().status();
    }
}
