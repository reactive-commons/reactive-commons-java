package org.reactivecommons.async.rabbit.listeners;

import java.time.Clock;
import java.time.Duration;
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
 * restarts keep failing without a successful delivery or a stable registration in between.
 */
public final class ListenerLiveness {

    /**
     * How long a consumer must stay registered for its registration to prove the listener works, without waiting for
     * a message (a queue without traffic delivers none). A consumer that the broker drops sooner keeps counting as a
     * failed restart, so a listener that registers and fails over and over is still reported broken.
     */
    public static final Duration STABLE_REGISTRATION = Duration.ofSeconds(5);

    private final String queueName;
    private final Clock clock;
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
        this(queueName, Clock.systemUTC());
    }

    ListenerLiveness(String queueName, Clock clock) {
        this.queueName = queueName;
        this.clock = clock;
    }

    public String queueName() {
        return queueName;
    }

    /**
     * Called once the broker has registered the consumer ({@code basic.consume-ok}). Marks the listener as active
     * and clears any outstanding down marker. It does not reset the failure counter by itself: a registration only
     * proves the listener works once it lasted {@link #STABLE_REGISTRATION}, or when a message arrives.
     */
    public void onSubscribed(String tag) {
        consumerTag.set(tag);
        if (active.compareAndSet(false, true)) {
            activeSince.set(clock.instant());
            downSince.set(null);
        }
    }

    /**
     * Called when the subscription terminated (channel closed, consumer cancelled, upstream error). Increments
     * {@code restartCount} and records the cause so the next failure to re-subscribe can be surfaced by the
     * health indicator.
     */
    public void onTerminated(Throwable cause) {
        final Instant now = clock.instant();
        if (wasRegisteredStably(now)) {
            clearFailures(); // it worked for a while: this termination starts a new failure history
        }
        if (active.compareAndSet(true, false)) {
            downSince.set(now);
        }
        restartCount.incrementAndGet();
        if (cause != null) {
            lastRestartFailure.set(now);
            lastRestartFailureMessage.set(String.valueOf(cause));
            consecutiveRestartFailures.incrementAndGet();
        }
    }

    /**
     * Called every time the listener successfully delivers a message to its handler. Resets the failure counter:
     * a working listener should never look broken.
     */
    public void onDelivery() {
        lastDeliveryAt.set(clock.instant());
        clearFailures();
    }

    private boolean wasRegisteredStably(Instant now) {
        final Instant since = activeSince.get();
        return active.get() && since != null && !since.isAfter(now.minus(STABLE_REGISTRATION));
    }

    private void clearFailures() {
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
