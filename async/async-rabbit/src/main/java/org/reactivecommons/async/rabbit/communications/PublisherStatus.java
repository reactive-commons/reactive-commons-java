package org.reactivecommons.async.rabbit.communications;

import java.time.Instant;

/**
 * In-memory liveness of the publishing flows of a {@link ReactiveMessageSender}. It lets a health check detect a
 * broken publishing path (e.g. flows endlessly re-subscribing because the broker rejects them, or the broker
 * back-pressuring with a {@code connection.blocked} alarm) even while the connection itself is open.
 *
 * @param confirmFlowsLive          whether at least one publisher-confirms flow is subscribed and holding a channel
 * @param noConfirmFlowLive         whether the no-confirm flow is subscribed and holding a channel
 * @param consecutiveConfirmFailures confirm-flow failures since the last successful confirmation: a value that keeps
 *                                  growing (e.g. flows cycling on a channel-level error) marks a broken publishing
 *                                  path even while the flows are momentarily subscribed
 * @param lastConfirmFailure        when a confirm flow last terminated and started re-subscribing, or {@code null}
 * @param lastConfirmFailureMessage the failure of the last re-subscription attempt, or {@code null}
 * @param confirmFlowsDownSince     when the last live confirm flow went down, or {@code null} while at least one is
 *                                  live
 * @param connectionBlocked         whether the broker has signalled {@code connection.blocked} and not yet
 *                                  {@code connection.unblocked} (memory/disk watermark alarm)
 * @param connectionBlockedSince    when the block began, or {@code null} while not blocked
 * @param connectionBlockedReason   broker-provided reason for the block (e.g. {@code "low on memory"}), or {@code null}
 */
public record PublisherStatus(boolean confirmFlowsLive,
                              boolean noConfirmFlowLive,
                              long consecutiveConfirmFailures,
                              Instant lastConfirmFailure,
                              String lastConfirmFailureMessage,
                              Instant confirmFlowsDownSince,
                              boolean connectionBlocked,
                              Instant connectionBlockedSince,
                              String connectionBlockedReason) {

    /**
     * Status of a sender with no degradation signal.
     */
    public static PublisherStatus healthy() {
        return new PublisherStatus(true, true, 0, null, null, null, false, null, null);
    }
}
