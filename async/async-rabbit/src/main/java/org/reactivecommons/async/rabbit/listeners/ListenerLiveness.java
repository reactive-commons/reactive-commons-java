package org.reactivecommons.async.rabbit.listeners;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory liveness of a single listener (one queue, one consumer tag). Mirrors the publisher-side
 * {@code ConnectionBlockedState}: atomics only, safe to update from the broker I/O thread, snapshotted by the
 * health indicator without blocking.
 *
 * <p>A listener is considered broken when it has been inactive longer than the configured grace, or when its
 * restarts keep failing without a successful delivery in between.
 */
public final class ListenerLiveness {

    private final String queueName;
    private final AtomicReference<String> consumerTag = new AtomicReference<>();
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicReference<Instant> activeSince = new AtomicReference<>();
    private final AtomicReference<Instant> downSince = new AtomicReference<>();
    private final AtomicReference<Instant> lastDeliveryAt = new AtomicReference<>();
    private final AtomicLong restartCount = new AtomicLong();
    private final AtomicLong consecutiveRestartFailures = new AtomicLong();
    private final AtomicReference<Instant> lastRestartFailure = new AtomicReference<>();
    private final AtomicReference<String> lastRestartFailureMessage = new AtomicReference<>();

    public ListenerLiveness(String queueName) {
        this.queueName = queueName;
    }

    public String queueName() {
        return queueName;
    }

    /**
     * Called when the listener's channel is open and the consumer tag has been assigned. Marks the listener as
     * active and clears any outstanding down marker. Does not reset the failure counter: that is cleared only when
     * a message actually arrives, so a listener that endlessly re-subscribes without ever receiving is still
     * reported as broken.
     */
    public void onSubscribed(String tag) {
        consumerTag.set(tag);
        if (active.compareAndSet(false, true)) {
            activeSince.set(Instant.now());
            downSince.set(null);
        }
    }

    /**
     * Called when the subscription terminated (channel closed, consumer cancelled, upstream error). Increments
     * {@code restartCount} and records the cause so the next failure to re-subscribe can be surfaced by the
     * health indicator.
     */
    public void onTerminated(Throwable cause) {
        if (active.compareAndSet(true, false)) {
            downSince.set(Instant.now());
        }
        restartCount.incrementAndGet();
        if (cause != null) {
            lastRestartFailure.set(Instant.now());
            lastRestartFailureMessage.set(String.valueOf(cause));
            consecutiveRestartFailures.incrementAndGet();
        }
    }

    /**
     * Called every time the listener successfully delivers a message to its handler. Resets the failure counter:
     * a working listener should never look broken.
     */
    public void onDelivery() {
        lastDeliveryAt.set(Instant.now());
        consecutiveRestartFailures.set(0);
        lastRestartFailure.set(null);
        lastRestartFailureMessage.set(null);
    }

    public SubscriberStatus snapshot() {
        return new SubscriberStatus(
                queueName,
                consumerTag.get(),
                active.get(),
                activeSince.get(),
                downSince.get(),
                lastDeliveryAt.get(),
                restartCount.get(),
                consecutiveRestartFailures.get(),
                lastRestartFailure.get(),
                lastRestartFailureMessage.get());
    }

    public boolean isActive() {
        return active.get();
    }

    public long consecutiveRestartFailures() {
        return consecutiveRestartFailures.get();
    }
}
