package org.reactivecommons.async.kafka.communications;

import java.time.Duration;

/**
 * The limits of the sends of a {@link ReactiveMessageSender}.
 *
 * @param sendTimeout     how long a send waits for its outcome before failing
 * @param maxPendingSends how many sends may wait for their outcome at once; beyond it a send fails at once
 */
public record SendLimits(Duration sendTimeout, int maxPendingSends) {

    public SendLimits {
        if (sendTimeout == null || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("sendTimeout must be positive: " + sendTimeout);
        }
        if (maxPendingSends < 1) {
            throw new IllegalArgumentException("maxPendingSends must be positive: " + maxPendingSends);
        }
    }
}
