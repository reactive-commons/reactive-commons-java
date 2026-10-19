package org.reactivecommons.async.rabbit.communications;

import java.time.Instant;

/**
 * In-memory liveness of the publishing flows of a {@link ReactiveMessageSender}. It lets a health check detect a
 * broken publishing path (e.g. flows endlessly re-subscribing because the broker rejects them) even while the
 * connection itself is open.
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
 */
public record PublisherStatus(boolean confirmFlowsLive,
                              boolean noConfirmFlowLive,
                              long consecutiveConfirmFailures,
                              Instant lastConfirmFailure,
                              String lastConfirmFailureMessage,
                              Instant confirmFlowsDownSince) {

    /**
     * Status of a sender with no degradation signal.
     */
    public static PublisherStatus healthy() {
        return new PublisherStatus(true, true, 0, null, null, null);
    }
}
