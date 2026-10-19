package org.reactivecommons.async.rabbit.listeners;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ListenerLivenessTest {

    @Test
    void startsInactiveWithNoHistory() {
        var liveness = new ListenerLiveness("app.events");

        var snapshot = liveness.snapshot();
        assertThat(snapshot.queueName()).isEqualTo("app.events");
        assertThat(snapshot.active()).isFalse();
        assertThat(snapshot.activeSince()).isNull();
        assertThat(snapshot.downSince()).isNull();
        assertThat(snapshot.lastDeliveryAt()).isNull();
        assertThat(snapshot.restartCount()).isZero();
        assertThat(snapshot.consecutiveRestartFailures()).isZero();
    }

    @Test
    void onSubscribedMarksActiveAndRecordsTag() {
        var liveness = new ListenerLiveness("app.events");
        Instant before = Instant.now();

        liveness.onSubscribed("tag-1");

        var snapshot = liveness.snapshot();
        assertThat(snapshot.active()).isTrue();
        assertThat(snapshot.consumerTag()).isEqualTo("tag-1");
        assertThat(snapshot.activeSince()).isAfterOrEqualTo(before);
    }

    @Test
    void onTerminatedWithErrorIncrementsConsecutiveFailures() {
        var liveness = new ListenerLiveness("app.events");
        liveness.onSubscribed("tag-1");

        liveness.onTerminated(new RuntimeException("boom"));

        var snapshot = liveness.snapshot();
        assertThat(snapshot.active()).isFalse();
        assertThat(snapshot.downSince()).isNotNull();
        assertThat(snapshot.restartCount()).isEqualTo(1);
        assertThat(snapshot.consecutiveRestartFailures()).isEqualTo(1);
        assertThat(snapshot.lastRestartFailureMessage()).contains("boom");
    }

    @Test
    void onTerminatedWithoutCauseStillCountsRestartButNotAsFailure() {
        var liveness = new ListenerLiveness("app.events");
        liveness.onSubscribed("tag-1");

        liveness.onTerminated(null);

        var snapshot = liveness.snapshot();
        assertThat(snapshot.restartCount()).isEqualTo(1);
        assertThat(snapshot.consecutiveRestartFailures()).isZero();
        assertThat(snapshot.lastRestartFailureMessage()).isNull();
    }

    @Test
    void onDeliveryClearsFailureHistory() {
        var liveness = new ListenerLiveness("app.events");
        liveness.onSubscribed("tag-1");
        liveness.onTerminated(new RuntimeException("boom"));
        liveness.onSubscribed("tag-2");

        liveness.onDelivery();

        var snapshot = liveness.snapshot();
        assertThat(snapshot.lastDeliveryAt()).isNotNull();
        assertThat(snapshot.consecutiveRestartFailures()).isZero();
        assertThat(snapshot.lastRestartFailure()).isNull();
        assertThat(snapshot.lastRestartFailureMessage()).isNull();
    }

    @Test
    void countsConsecutiveFailuresUntilADeliverySucceeds() {
        var liveness = new ListenerLiveness("app.events");
        liveness.onSubscribed("t");
        liveness.onTerminated(new RuntimeException("1"));
        liveness.onTerminated(new RuntimeException("2"));
        liveness.onTerminated(new RuntimeException("3"));

        assertThat(liveness.consecutiveRestartFailures()).isEqualTo(3);
        assertThat(liveness.snapshot().restartCount()).isEqualTo(3);

        liveness.onDelivery();

        assertThat(liveness.consecutiveRestartFailures()).isZero();
        assertThat(liveness.snapshot().restartCount()).isEqualTo(3); // total count is not reset
    }

    @Test
    void repeatedOnSubscribedDoesNotRecordNewActiveSince() {
        var liveness = new ListenerLiveness("app.events");
        liveness.onSubscribed("tag-1");
        Instant first = liveness.snapshot().activeSince();

        liveness.onSubscribed("tag-1");

        assertThat(liveness.snapshot().activeSince()).isEqualTo(first);
    }
}
