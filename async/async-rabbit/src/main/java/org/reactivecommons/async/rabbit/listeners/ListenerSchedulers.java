package org.reactivecommons.async.rabbit.listeners;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.java.Log;
import org.jspecify.annotations.NonNull;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * The schedulers shared by every listener of the application, instead of a pair of pools per listener.
 *
 * <ul>
 *     <li>{@link #handlers()} runs the handlers. It is a pool of up to {@value #MAX_HANDLER_THREADS} threads (the
 *     default {@code maxConcurrency} and {@code prefetchCount}) that starts with one thread per processor and only
 *     grows when tasks have been starving for more than {@value #STARVATION_CHECK_MILLIS} ms, and whose idle
 *     threads are released after a minute, so handlers that do not
 *     block use a few threads and handlers that block can run at the same time up to the limit.</li>
 *     <li>{@link #errorReporter()} runs the custom error reporter.</li>
 * </ul>
 * They are created when first used, and disposed when the last application using them is released (see
 * {@link #retain()} and {@link #release()}); they are created again if used afterwards, e.g. by another application
 * context in the same JVM.
 *
 * <p>While some application retaining them wants metrics, the handler pool is exported as
 * {@code reactive_commons.schedulers.handler.pool.size}, {@code ...threads.active}, {@code ...queue.size} (gauges)
 * and {@code ...growth} (the times the pool grew because its tasks were starving). The pools are shared by the JVM,
 * so these meters have no domain tag; the meters of an application's registry are removed when it releases them.
 */
@Log
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ListenerSchedulers {

    public static final int MAX_HANDLER_THREADS = 250;
    private static final int KEEP_ALIVE_SECONDS = 60;
    private static final long STARVATION_CHECK_MILLIS = 5;
    private static final int ERROR_REPORTER_THREADS = 4;
    private static final int ERROR_REPORTER_QUEUE = 256;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    private static final String M_POOL_SIZE = "reactive_commons.schedulers.handler.pool.size";
    private static final String M_THREADS_ACTIVE = "reactive_commons.schedulers.handler.threads.active";
    private static final String M_QUEUE_SIZE = "reactive_commons.schedulers.handler.queue.size";
    private static final String M_GROWTH = "reactive_commons.schedulers.handler.growth";

    private static final Object LOCK = new Object();
    private static ElasticExecutor executor; // guarded by LOCK
    private static volatile Scheduler handlers;
    private static volatile Scheduler errorReporter;
    private static int users; // guarded by LOCK
    // the registries of the applications using the schedulers, and the meters registered on each: the pools are
    // shared JVM-wide, so a gauge can only exist while some application using them wants metrics
    private static final Set<MeterRegistry> registries =
            Collections.newSetFromMap(new IdentityHashMap<>()); // guarded by LOCK
    private static final Map<MeterRegistry, List<Meter.Id>> meterIds = new IdentityHashMap<>(); // guarded by LOCK

    /**
     * The scheduler that runs the handlers of every listener.
     *
     * @return the shared scheduler
     */
    public static Scheduler handlers() {
        Scheduler current = handlers;
        if (current == null || current.isDisposed()) {
            synchronized (LOCK) {
                current = handlers;
                if (current == null || current.isDisposed()) {
                    executor = new ElasticExecutor(MAX_HANDLER_THREADS, daemonThreads("rc-handlers"));
                    for (MeterRegistry registry : registries) {
                        removeMeterIds(registry); // the stale gauges pointed to the previous pool
                        registerSchedulerMetrics(registry, executor);
                    }
                    current = Schedulers.fromExecutor(executor);
                    handlers = current;
                }
            }
        }
        return current;
    }

    /**
     * The scheduler that runs the custom error reporter of every listener.
     *
     * @return the shared scheduler
     */
    public static Scheduler errorReporter() {
        Scheduler current = errorReporter;
        if (current == null || current.isDisposed()) {
            synchronized (LOCK) {
                current = errorReporter;
                if (current == null || current.isDisposed()) {
                    current = Schedulers.newBoundedElastic(ERROR_REPORTER_THREADS, ERROR_REPORTER_QUEUE,
                            "rc-error-reporter", KEEP_ALIVE_SECONDS, true);
                    errorReporter = current;
                }
            }
        }
        return current;
    }

    /**
     * The number of applications (Spring contexts) in this JVM that are using the shared schedulers.
     *
     * @return the number of applications that called {@link #retain()} and did not {@link #release()} yet
     */
    public static int activeApplications() {
        synchronized (LOCK) {
            return users;
        }
    }

    /**
     * Registers an application that uses the shared schedulers.
     */
    public static void retain() {
        retain(null);
    }

    /**
     * Registers an application that uses the shared schedulers and wants their metrics in the given registry.
     * The gauges are registered when the handler pool exists, which may be after this call.
     */
    public static void retain(MeterRegistry registry) {
        synchronized (LOCK) {
            users++;
            if (registry != null) {
                registries.add(registry);
                if (executor != null && !meterIds.containsKey(registry)) {
                    registerSchedulerMetrics(registry, executor);
                }
            }
        }
    }

    /**
     * Unregisters an application. When the last one is gone the schedulers are disposed: the handlers already
     * submitted are allowed to finish (up to {@value #SHUTDOWN_TIMEOUT_SECONDS} seconds).
     */
    public static void release() {
        release(null);
    }

    /**
     * Unregisters an application. The meters registered for its registry, if any, are removed even if other
     * applications still use the schedulers. When the last application is gone the schedulers are disposed and
     * every remaining meter is removed.
     */
    public static void release(MeterRegistry registry) {
        ElasticExecutor toStop;
        Scheduler reporter;
        synchronized (LOCK) {
            if (users > 0) {
                users--;
            }
            if (registry != null) {
                removeSchedulerMetrics(registry);
            }
            if (users > 0) {
                return;
            }
            for (MeterRegistry remaining : new ArrayList<>(registries)) {
                removeSchedulerMetrics(remaining);
            }
            toStop = executor;
            reporter = errorReporter;
            executor = null;
            handlers = null;
            errorReporter = null;
        }
        if (reporter != null) {
            reporter.dispose();
        }
        if (toStop != null) {
            stop(toStop);
        }
    }

    /**
     * Registers the gauges of the handler pool in the registry, unless they are already registered (the method is
     * called both when the pool is created and when an application retains the schedulers after that).
     */
    private static void registerSchedulerMetrics(MeterRegistry registry, ElasticExecutor pool) {
        // never let a mis-wired registry break the listeners; metrics are best-effort. The ids of the meters that
        // could be registered are kept even if another one fails, so they are removed on release.
        final List<Meter.Id> ids = new ArrayList<>(4);
        register(ids, () -> Gauge.builder(M_POOL_SIZE, pool, e -> e.getPoolSize()).register(registry).getId());
        register(ids, () -> Gauge.builder(M_THREADS_ACTIVE, pool, e -> e.getActiveCount()).register(registry).getId());
        register(ids, () -> Gauge.builder(M_QUEUE_SIZE, pool, e -> e.getQueue().size()).register(registry).getId());
        register(ids, () -> FunctionCounter.builder(M_GROWTH, pool, e -> e.growthCount()).register(registry).getId());
        if (!ids.isEmpty()) {
            meterIds.put(registry, ids);
        }
    }

    private static void register(List<Meter.Id> ids, Supplier<Meter.Id> registration) {
        try {
            ids.add(registration.get());
        } catch (Exception e) {
            log.log(Level.WARNING, e, () -> "Could not register a scheduler metric");
        }
    }

    private static void removeSchedulerMetrics(MeterRegistry registry) {
        registries.remove(registry);
        removeMeterIds(registry);
    }

    private static void removeMeterIds(MeterRegistry registry) {
        final List<Meter.Id> ids = meterIds.remove(registry);
        if (ids == null) {
            return;
        }
        try {
            ids.forEach(registry::remove);
        } catch (Exception e) {
            log.log(Level.WARNING, e, () -> "Could not remove the scheduler metrics");
        }
    }

    private static void stop(ElasticExecutor toStop) {
        toStop.shutdown();
        try {
            if (!toStop.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.log(Level.WARNING, "Handlers still running after {0} seconds, interrupting them",
                        SHUTDOWN_TIMEOUT_SECONDS);
                toStop.shutdownNow();
            }
        } catch (InterruptedException e) {
            toStop.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * A pool with a small base size (one thread per processor) that grows, up to its limit, only when the tasks are
     * starving: unlike the default {@link ThreadPoolExecutor}, it does not wait for a bounded queue to be full.
     * Handlers that do not block never leave the base size, handlers that block make it grow.
     *
     * <p>The submission path is just "put in a lock-free queue": nothing is measured there. A monitor, scheduled
     * only while there are queued tasks, looks every {@value ListenerSchedulers#STARVATION_CHECK_MILLIS} ms at the
     * head of the queue; if it is the same task as in the previous check, nobody took it for that long, so the
     * threads are really busy and the pool grows. A burst of tasks that finish in microseconds is drained before
     * that, so it does not create threads.</p>
     */
    private static final class ElasticExecutor extends ThreadPoolExecutor {

        private final ScheduledThreadPoolExecutor monitor;
        private final AtomicBoolean checkScheduled = new AtomicBoolean();
        private final AtomicLong growthCount = new AtomicLong();
        private Runnable headSeenInThePreviousCheck; // only used by the monitor thread

        ElasticExecutor(int maxThreads, ThreadFactory threadFactory) {
            super(baseThreads(maxThreads), maxThreads, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                    new GrowingQueue(), threadFactory, new AbortPolicy());
            ((GrowingQueue) getQueue()).owner = this;
            allowCoreThreadTimeOut(true);
            monitor = new ScheduledThreadPoolExecutor(1, daemonThreads("rc-handlers-monitor"));
            monitor.setKeepAliveTime(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
            monitor.allowCoreThreadTimeOut(true);
        }

        @Override
        public void shutdown() {
            monitor.shutdownNow();
            super.shutdown();
        }

        @Override
        public @NonNull List<Runnable> shutdownNow() {
            monitor.shutdownNow();
            return super.shutdownNow();
        }

        void scheduleStarvationCheck() {
            if (!checkScheduled.get() && checkScheduled.compareAndSet(false, true)) {
                try {
                    monitor.schedule(this::growIfStarving, STARVATION_CHECK_MILLIS, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    checkScheduled.set(false); // shut down meanwhile
                }
            }
        }

        private void growIfStarving() {
            checkScheduled.set(false);
            final BlockingQueue<Runnable> queue = getQueue();
            final Runnable head = queue.peek();
            if (head != null && head == headSeenInThePreviousCheck) {
                // at most quadruples the pool on every check: it reaches the limit in a few checks when the handlers
                // block, but a pause of the JVM (that makes the queued tasks look stuck) adds just a few threads
                final int poolSize = getPoolSize();
                final int step = Math.min(queue.size(), Math.max(baseThreads(getMaximumPoolSize()), poolSize * 3));
                final int target = Math.min(getMaximumPoolSize(), poolSize + step);
                if (target > poolSize) {
                    // the new threads take the waiting tasks, and go away when idle for a minute
                    growthCount.incrementAndGet();
                    setCorePoolSize(target);
                    prestartAllCoreThreads();
                    setCorePoolSize(baseThreads(getMaximumPoolSize()));
                }
            }
            headSeenInThePreviousCheck = head;
            if (head != null) {
                scheduleStarvationCheck();
            }
        }

        private static int baseThreads(int maxThreads) {
            return Math.min(maxThreads, Math.max(2, Runtime.getRuntime().availableProcessors()));
        }

        long growthCount() {
            return growthCount.get();
        }
    }

    /**
     * An unbounded lock-free queue that asks the pool to watch for starving tasks every time one is queued.
     */
    private static final class GrowingQueue extends LinkedTransferQueue<Runnable> {
        private transient volatile ElasticExecutor owner;

        @Override
        public boolean offer(Runnable task) {
            final boolean queued = super.offer(task);
            final ElasticExecutor pool = owner;
            if (pool != null) {
                pool.scheduleStarvationCheck();
            }
            return queued;
        }
    }
}
