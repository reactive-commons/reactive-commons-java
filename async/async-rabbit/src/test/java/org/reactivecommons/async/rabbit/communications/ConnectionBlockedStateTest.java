package org.reactivecommons.async.rabbit.communications;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionBlockedStateTest {

    @Test
    void startsUnblocked() {
        var state = new ConnectionBlockedState();

        assertThat(state.isBlocked()).isFalse();
        assertThat(state.blockedSince()).isNull();
        assertThat(state.blockedReason()).isNull();
        assertThat(state.blockCount()).isZero();
    }

    @Test
    void recordsTheBlockAndItsReason() {
        var state = new ConnectionBlockedState();
        Instant before = Instant.now();

        state.onBlocked("low on memory");

        assertThat(state.isBlocked()).isTrue();
        assertThat(state.blockedReason()).isEqualTo("low on memory");
        assertThat(state.blockedSince()).isAfterOrEqualTo(before);
        assertThat(state.blockCount()).isEqualTo(1);
    }

    @Test
    void doesNotCountAReBlockWhileAlreadyBlocked() {
        var state = new ConnectionBlockedState();
        state.onBlocked("low on memory");
        Instant firstBlockedSince = state.blockedSince();

        state.onBlocked("low on disk"); // broker is allowed to update the reason without a prior unblock

        assertThat(state.isBlocked()).isTrue();
        assertThat(state.blockedReason()).isEqualTo("low on disk");
        assertThat(state.blockedSince()).isEqualTo(firstBlockedSince);
        assertThat(state.blockCount()).isEqualTo(1);
    }

    @Test
    void clearsOnUnblock() {
        var state = new ConnectionBlockedState();
        state.onBlocked("low on memory");

        state.onUnblocked();

        assertThat(state.isBlocked()).isFalse();
        assertThat(state.blockedSince()).isNull();
        assertThat(state.blockedReason()).isNull();
        assertThat(state.blockCount()).isEqualTo(1); // the past block still counts
    }

    @Test
    void unblockWhileNotBlockedIsANoOp() {
        var state = new ConnectionBlockedState();

        state.onUnblocked();

        assertThat(state.isBlocked()).isFalse();
        assertThat(state.blockCount()).isZero();
    }

    @Test
    void countsEachBlockUnblockCycle() {
        var state = new ConnectionBlockedState();

        state.onBlocked("low on memory");
        state.onUnblocked();
        state.onBlocked("low on disk");
        state.onUnblocked();
        state.onBlocked("low on memory");

        assertThat(state.isBlocked()).isTrue();
        assertThat(state.blockCount()).isEqualTo(3);
    }
}
