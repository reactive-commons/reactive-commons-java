package org.reactivecommons.async.rabbit.listeners;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Schedules a new subscription of a listener after the current one terminated. Uses an exponential backoff with the
 * same bounds as the sender's confirm-flow re-subscription (200 ms → 5 s). The first restart of the listener is
 * logged at WARN so an outage is visible; subsequent attempts log at DEBUG to avoid flooding when the broker is
 * continuously unavailable. The restart runs on a separate scheduler so it never recurses from the termination
 * callback and overflows the stack if the new subscription fails synchronously.
 */
@Slf4j
final class ListenerRestarts {

    static final Duration RESTART_MIN_BACKOFF = Duration.ofMillis(200);
    static final Duration RESTART_MAX_BACKOFF = Duration.ofSeconds(5);

    private final String listenerName;
    private final ListenerLiveness liveness;
    private final ConsumerMetrics metrics;
    private final BooleanSupplier stopped;
    private final Runnable subscribe;
    private final AtomicBoolean restartScheduled = new AtomicBoolean(false);
    private final AtomicBoolean firstRestartWarning = new AtomicBoolean();

    /**
     * @param listenerName how the restarts name the listener, e.g. "listener for queue app.subsEvents"
     * @param stopped      whether the listener was stopped: it is not restarted then
     * @param subscribe    subscribes the listener again
     */
    ListenerRestarts(String listenerName, ListenerLiveness liveness, ConsumerMetrics metrics, BooleanSupplier stopped,
                     Runnable subscribe) {
        this.listenerName = listenerName;
        this.liveness = liveness;
        this.metrics = metrics;
        this.stopped = stopped;
        this.subscribe = subscribe;
    }

    void schedule(String reason) {
        if (stopped.getAsBoolean() || !restartScheduled.compareAndSet(false, true)) {
            return; // the restart already scheduled covers this trigger too: counted once, with the first reason
        }
        metrics.recordRestart(reason);
        long consecutive = liveness.consecutiveRestartFailures();
        long delayMs = backoffMillis(consecutive);
        Level level = consecutive <= 1 && firstRestartWarning.compareAndSet(false, true)
                ? Level.WARN : Level.DEBUG;
        log.atLevel(level).log("Re-subscribing {} in {} ms (attempt {})", listenerName, delayMs,
                Math.max(1, consecutive));
        Schedulers.boundedElastic().schedule(() -> {
            restartScheduled.set(false);
            subscribe.run();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    static long backoffMillis(long consecutive) {
        if (consecutive <= 0) {
            return RESTART_MIN_BACKOFF.toMillis();
        }
        long shift = Math.min(consecutive - 1, 30);
        long millis = RESTART_MIN_BACKOFF.toMillis() << shift;
        if (millis < 0) {
            return RESTART_MAX_BACKOFF.toMillis();
        }
        return Math.min(millis, RESTART_MAX_BACKOFF.toMillis());
    }
}
