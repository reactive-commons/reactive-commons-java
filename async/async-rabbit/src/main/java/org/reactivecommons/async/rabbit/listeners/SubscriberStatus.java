package org.reactivecommons.async.rabbit.listeners;

import java.time.Instant;

/**
 * In-memory liveness snapshot of one listener, consumed by the health indicator. It is the subscriber-side
 * equivalent of {@code PublisherStatus}: a broken consumer (inactive beyond the grace, or restarts failing
 * without a successful delivery in between) can be told apart from a healthy one without network I/O.
 *
 * @param queueName                   the queue this listener consumes from
 * @param consumerTag                 last known consumer tag assigned by the broker, or {@code null} before the
 *                                    first subscribe
 * @param active                      whether the listener currently holds an open consumer on an open channel
 * @param activeSince                 when the listener last became active, or {@code null} while never-active
 * @param downSince                   when the listener last went inactive, or {@code null} while active
 * @param lastDeliveryAt              timestamp of the last message successfully handed to the handler, or
 *                                    {@code null} when no message has arrived yet
 * @param restartCount                total subscription restarts since the listener started
 * @param consecutiveRestartFailures  restarts since the last successful delivery - a value that keeps growing
 *                                    marks a broken listener even when its channel momentarily subscribes
 * @param lastRestartFailure          when the subscription last terminated with an error, or {@code null}
 * @param lastRestartFailureMessage   stringified cause of the last restart, or {@code null}
 */
public record SubscriberStatus(String queueName,
                               String consumerTag,
                               boolean active,
                               Instant activeSince,
                               Instant downSince,
                               Instant lastDeliveryAt,
                               long restartCount,
                               long consecutiveRestartFailures,
                               Instant lastRestartFailure,
                               String lastRestartFailureMessage) {

    public static SubscriberStatus healthy(String queueName) {
        return new SubscriberStatus(queueName, null, true, Instant.now(), null, null, 0, 0, null, null);
    }
}
