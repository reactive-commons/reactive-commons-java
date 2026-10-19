package org.reactivecommons.async.rabbit.communications;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory liveness of the RabbitMQ {@code connection.blocked} / {@code connection.unblocked} notifications. The
 * broker raises a block when it hits a resource alarm (memory or disk watermark); while blocked, {@code basicPublish}
 * calls stall in the client's socket buffer. This class turns that signal into observable state so the health check,
 * metrics, and the sender's fail-fast path can react.
 *
 * <p>Instances are safe for concurrent use. {@link #onBlocked(String)} and {@link #onUnblocked()} are invoked from
 * the Rabbit client I/O thread, which is why they do nothing but update atomics.
 */
public final class ConnectionBlockedState {

    private final AtomicBoolean blocked = new AtomicBoolean();
    private final AtomicReference<Instant> blockedSince = new AtomicReference<>();
    private final AtomicReference<String> blockedReason = new AtomicReference<>();
    private final AtomicLong blockCount = new AtomicLong();

    public void onBlocked(String reason) {
        blockedReason.set(reason);
        if (blocked.compareAndSet(false, true)) {
            blockedSince.set(Instant.now());
            blockCount.incrementAndGet();
        }
    }

    public void onUnblocked() {
        if (blocked.compareAndSet(true, false)) {
            blockedSince.set(null);
            blockedReason.set(null);
        }
    }

    public boolean isBlocked() {
        return blocked.get();
    }

    public Instant blockedSince() {
        return blockedSince.get();
    }

    public String blockedReason() {
        return blockedReason.get();
    }

    public long blockCount() {
        return blockCount.get();
    }
}
