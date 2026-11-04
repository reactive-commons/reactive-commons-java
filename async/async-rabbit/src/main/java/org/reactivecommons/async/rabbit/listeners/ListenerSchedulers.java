package org.reactivecommons.async.rabbit.listeners;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.AbstractOwnableSynchronizer;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The schedulers shared by every listener of the application, instead of a pair of pools per listener.
 *
 * <ul>
 *     <li>{@link #handlers()} runs the handlers. It is a pool that starts with one thread per processor and only
 *     grows when tasks have been starving for more than {@value #STARVATION_CHECK_MILLIS} ms while its busy threads
 *     wait for their own work (I/O), not for a resource they share (a lock, a monitor: more threads would only queue
 *     up behind it). The threads it did not need during the last {@value #HANDLER_KEEP_ALIVE_SECONDS} seconds are
 *     released, even while tasks keep coming, so handlers that do not block use a few threads and handlers that block
 *     can run at the same time up to the limit. The
 *     limit is the sum of the {@code maxConcurrency} of the active listeners (see {@link #registerListener(int)}),
 *     and at least {@value #MAX_HANDLER_THREADS} (the default {@code maxConcurrency} and {@code prefetchCount}): a
 *     listener whose handlers block can never take the threads the other listeners need.</li>
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
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ListenerSchedulers {

    public static final int MAX_HANDLER_THREADS = 250;
    private static final int KEEP_ALIVE_SECONDS = 60;
    private static final int HANDLER_KEEP_ALIVE_SECONDS = 15;
    private static final long STARVATION_CHECK_MILLIS = 5;
    /**
     * Starvation checks in a row without any task completing (at least a second) after which the pool grows even if
     * its busy threads wait together.
     */
    private static final int STALL_CHECKS = 200;
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
    private static int listenersConcurrency; // guarded by LOCK: sum of the maxConcurrency of the active listeners
    // the registries of the applications using the schedulers (with the number of applications retaining each, as
    // contexts may share one), and the meters registered on each: the pools are shared JVM-wide, so a gauge can
    // only exist while some application using them wants metrics
    private static final Map<MeterRegistry, Integer> registries = new IdentityHashMap<>(); // guarded by LOCK
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
                    executor = new ElasticExecutor(handlerThreadsLimit(), "rc-handlers");
                    for (MeterRegistry registry : registries.keySet()) {
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
     * Makes room in the handler pool for the deliveries a listener handles at the same time: the handlers of a
     * listener never wait for the threads taken by the handlers of another one. Call {@link #unregisterListener(int)}
     * with the same value when the listener stops.
     *
     * @param maxConcurrency the deliveries the listener handles at the same time at most
     */
    public static void registerListener(int maxConcurrency) {
        synchronized (LOCK) {
            listenersConcurrency += maxConcurrency;
            if (executor != null) {
                executor.setThreadsLimit(handlerThreadsLimit());
            }
        }
    }

    /**
     * Releases the room a stopped listener had in the handler pool.
     *
     * @param maxConcurrency the value given to {@link #registerListener(int)}
     */
    public static void unregisterListener(int maxConcurrency) {
        synchronized (LOCK) {
            listenersConcurrency = Math.max(0, listenersConcurrency - maxConcurrency);
            if (executor != null) {
                executor.setThreadsLimit(handlerThreadsLimit());
            }
        }
    }

    /**
     * The most threads the handler pool may have now.
     *
     * @return the sum of the maxConcurrency of the active listeners, at least {@value #MAX_HANDLER_THREADS}
     */
    public static int handlerThreadsLimit() {
        synchronized (LOCK) {
            return Math.max(MAX_HANDLER_THREADS, listenersConcurrency);
        }
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
                registries.merge(registry, 1, Integer::sum);
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
     * Unregisters an application. The meters registered for its registry, if any, are removed once no other
     * application retaining the same registry remains, even if other applications still use the schedulers. When
     * the last application is gone the schedulers are disposed and every remaining meter is removed.
     */
    public static void release(MeterRegistry registry) {
        ElasticExecutor toStop;
        Scheduler reporter;
        synchronized (LOCK) {
            if (users > 0) {
                users--;
            }
            if (registry != null) {
                final Integer retaining = registries.get(registry);
                if (retaining != null && retaining > 1) {
                    registries.put(registry, retaining - 1); // another application still uses this registry
                } else {
                    registries.remove(registry);
                    removeMeterIds(registry);
                }
            }
            if (users > 0) {
                return;
            }
            for (MeterRegistry remaining : new ArrayList<>(registries.keySet())) {
                removeMeterIds(remaining);
            }
            registries.clear();
            listenersConcurrency = 0; // the listeners of the released applications are stopped
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
        register(ids, () -> Gauge.builder(M_POOL_SIZE, pool, ElasticExecutor::getPoolSize).register(registry).getId());
        register(ids, () -> Gauge.builder(M_THREADS_ACTIVE, pool, ElasticExecutor::getActiveCount)
                .register(registry).getId());
        register(ids, () -> Gauge.builder(M_QUEUE_SIZE, pool, e -> e.getQueue().size()).register(registry).getId());
        register(ids, () -> FunctionCounter.builder(M_GROWTH, pool, ElasticExecutor::growthCount)
                .register(registry).getId());
        if (!ids.isEmpty()) {
            meterIds.put(registry, ids);
        }
    }

    private static void register(List<Meter.Id> ids, Supplier<Meter.Id> registration) {
        try {
            ids.add(registration.get());
        } catch (Exception e) {
            log.warn("Could not register a scheduler metric", e);
        }
    }

    private static void removeMeterIds(MeterRegistry registry) {
        final List<Meter.Id> ids = meterIds.remove(registry);
        if (ids == null) {
            return;
        }
        try {
            ids.forEach(registry::remove);
        } catch (Exception e) {
            log.warn("Could not remove the scheduler metrics", e);
        }
    }

    private static void stop(ElasticExecutor toStop) {
        toStop.shutdown();
        try {
            if (!toStop.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Handlers still running after {} seconds, interrupting them", SHUTDOWN_TIMEOUT_SECONDS);
                toStop.shutdownNow();
            }
        } catch (InterruptedException e) {
            toStop.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A thread pool with a small base size (one thread per processor) that grows up to its limit only when task
     * starvation is detected.
     *
     * <p>Growth:</p>
     * <ul>
     *     <li>Unlike {@link ThreadPoolExecutor}, it does not wait for a bounded queue to fill.</li>
     *     <li>Non-blocking tasks stay within the base size; blocking tasks trigger pool growth.</li>
     *     <li>A monitor checks the head of the queue every {@value ListenerSchedulers#STARVATION_CHECK_MILLIS} ms. If
     *     the same task remains stuck, it infers starvation and grows (up to four times its size per check, as many
     *     threads as tasks wait). Short bursts of fast tasks do not create threads.</li>
     * </ul>
     *
     * <p>Blocking constraints:</p>
     * <ul>
     *     <li>The pool grows if threads wait for independent work (individual I/O, futures), as seen in two checks in a
     *     row: a single look may catch a thread between two tasks, or a lock being handed over.</li>
     *     <li>It does not grow if threads contend for a shared resource (a monitor or a lock another thread holds, e.g.
     *     synchronous log appenders), to prevent memory bloat and GC pauses.</li>
     *     <li>Exception: if no task completes during {@value ListenerSchedulers#STALL_CHECKS} checks in a row (at
     *     least a second), a deadlock or latch is assumed, and the pool grows anyway to force progress. Checks are
     *     counted, not time: on a busy host or a throttled container most checks run late, and they must still add
     *     up.</li>
     *     <li>A check delayed by a system pause (GC, container CPU quota) does not make the pool grow: the tasks only
     *     look stuck. It still counts for the exception above.</li>
     * </ul>
     *
     * <p>Shrinking:</p>
     * <ul>
     *     <li>The pool shrinks to match recent usage, even under a steady task flow.</li>
     *     <li>A thread asking for its next task leaves if the queue is empty and the pool has more threads than the
     *     peak number of concurrent active threads during the last keep-alive period (plus a 25% margin), never below
     *     the base size nor while the pool grows.</li>
     * </ul>
     */
    static final class ElasticExecutor extends ThreadPoolExecutor {

        private final ScheduledThreadPoolExecutor monitor;
        private final Workers workers;
        private final LongSupplier clock;
        private final long windowNanos;
        private final AtomicBoolean checkScheduled = new AtomicBoolean();
        private final AtomicLong growthCount = new AtomicLong();
        private final AtomicInteger busyThreads = new AtomicInteger();
        // the most handlers that ran at the same time in the current period of windowNanos, and in the previous one
        private final AtomicInteger windowPeak = new AtomicInteger();
        private final AtomicLong windowEndsAt;
        private volatile int previousWindowPeak;
        private volatile int base;
        private volatile long checkDueAt;
        // only used by the monitor thread
        private Runnable headSeenInThePreviousCheck;
        private long completedAtLastCheck;
        private int checksWithoutProgress;
        private boolean apartInThePreviousSample;

        ElasticExecutor(int maxThreads, String threadPrefix) {
            this(maxThreads, threadPrefix, Duration.ofSeconds(HANDLER_KEEP_ALIVE_SECONDS), System::nanoTime);
        }

        /**
         * @param keepAlive how long the threads not needed are kept: idle, or above the most handlers that ran at the
         *                  same time during that period
         * @param clock     the {@link System#nanoTime()} the starvation checks and the periods are measured with
         */
        ElasticExecutor(int maxThreads, String threadPrefix, Duration keepAlive, LongSupplier clock) {
            this(maxThreads, new Workers(threadPrefix), keepAlive, clock);
        }

        private ElasticExecutor(int maxThreads, Workers workers, Duration keepAlive, LongSupplier clock) {
            super(baseThreads(maxThreads), maxThreads, keepAlive.toNanos(), TimeUnit.NANOSECONDS,
                    new GrowingQueue(), workers, new AbortPolicy());
            this.workers = workers;
            this.clock = clock;
            this.windowNanos = keepAlive.toNanos();
            this.windowEndsAt = new AtomicLong(clock.getAsLong() + windowNanos);
            this.base = baseThreads(maxThreads);
            ((GrowingQueue) getQueue()).owner = this;
            allowCoreThreadTimeOut(true);
            monitor = new ScheduledThreadPoolExecutor(1, daemonThreads("rc-handlers-monitor"));
            monitor.setKeepAliveTime(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
            monitor.allowCoreThreadTimeOut(true);
        }

        @Override
        protected void beforeExecute(Thread thread, Runnable task) {
            if (thread instanceof HandlerThread handler) {
                handler.busy = true;
            }
            final int busy = busyThreads.incrementAndGet();
            if (busy > windowPeak.get()) {
                windowPeak.accumulateAndGet(busy, Math::max);
            }
        }

        @Override
        protected void afterExecute(Runnable task, Throwable error) {
            busyThreads.decrementAndGet();
            if (Thread.currentThread() instanceof HandlerThread handler) {
                handler.busy = false;
            }
        }

        /**
         * Whether the thread asking for its next task should leave instead: no task is waiting, and the pool has more
         * threads than its handlers needed lately. Called by the threads of the pool before they wait for a task.
         */
        boolean releaseIfSurplus() {
            if (!(Thread.currentThread() instanceof HandlerThread thread)) {
                return false;
            }
            if (thread.released) {
                // the pool kept it after the release (it was its last thread and a task came in): it stays
                thread.released = false;
                workers.live.incrementAndGet();
            }
            final int floor = Math.max(base, getCorePoolSize());
            if (workers.live.get() <= floor || !getQueue().isEmpty()) {
                return false;
            }
            final long now = clock.getAsLong();
            final long end = windowEndsAt.get();
            if (now - end >= 0 && windowEndsAt.compareAndSet(end, now + windowNanos)) {
                previousWindowPeak = windowPeak.getAndSet(busyThreads.get());
            }
            final int recentPeak = Math.max(previousWindowPeak, windowPeak.get());
            final int needed = Math.max(floor, recentPeak + recentPeak / 4);
            for (int live = workers.live.get(); live > needed; live = workers.live.get()) {
                if (workers.live.compareAndSet(live, live - 1)) {
                    thread.released = true;
                    return true;
                }
            }
            return false;
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
                checkDueAt = clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(STARVATION_CHECK_MILLIS);
                try {
                    monitor.schedule(this::growIfStarving, STARVATION_CHECK_MILLIS, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    checkScheduled.set(false); // shut down meanwhile
                }
            }
        }

        private void growIfStarving() {
            checkScheduled.set(false);
            final long now = clock.getAsLong();
            // late by more than the period between two checks: the process was paused, the head only looks stuck
            final boolean late = now - checkDueAt > TimeUnit.MILLISECONDS.toNanos(STARVATION_CHECK_MILLIS);
            final BlockingQueue<Runnable> queue = getQueue();
            final Runnable head = queue.peek();
            final boolean stalled = stalled(headSeenInThePreviousCheck == null);
            final boolean stuck = head != null && head == headSeenInThePreviousCheck;
            // at its limit the pool cannot grow: the busy threads are not even sampled
            if (stuck && getPoolSize() < getMaximumPoolSize() && (stalled || (!late && waitApartTwiceInARow()))) {
                grow(queue.size());
                // the new threads get as many checks before the pool grows again for lack of progress
                checksWithoutProgress = 0;
            }
            headSeenInThePreviousCheck = head;
            if (head != null) {
                scheduleStarvationCheck();
            } else {
                apartInThePreviousSample = false; // the starving episode is over
            }
        }

        /**
         * Whether the busy threads wait apart (each for its own work) in this sample and in the previous one of the same
         * starving episode. A single sample may catch a thread between two tasks, or a lock being handed over to the
         * next thread: with two threads, that alone would look like threads waiting apart.
         */
        private boolean waitApartTwiceInARow() {
            final boolean apart = !busyThreadsWaitTogether();
            final boolean confirmed = apart && apartInThePreviousSample;
            apartInThePreviousSample = apart;
            return confirmed;
        }

        /**
         * Whether no task completed during the last {@value ListenerSchedulers#STALL_CHECKS} checks, counting from this
         * one when {@code restart} (a new starving episode).
         */
        private boolean stalled(boolean restart) {
            final long completed = getCompletedTaskCount();
            if (restart || completed != completedAtLastCheck) {
                completedAtLastCheck = completed;
                checksWithoutProgress = 0;
                return false;
            }
            return ++checksWithoutProgress >= STALL_CHECKS;
        }

        /**
         * Whether at least half of the busy threads wait for a resource another thread holds or also waits for: a
         * monitor (the thread is {@code BLOCKED}), a lock another thread owns (a {@code ReentrantLock}, as the
         * appenders of Logback use), or the same semaphore or condition as another busy thread (the same
         * {@link LockSupport#getBlocker(Thread) blocker}). A thread that waits for its own work (sleeping, reading a
         * socket, or parked on its own latch or future) does not count. Reading the state and the blocker of a thread
         * is two field reads; the JVM is asked for the owner of a lock a thread waits for alone (about a microsecond
         * per thread, without pausing it), only when it can change the answer. This runs only while tasks are
         * starving, once per check.
         */
        boolean busyThreadsWaitTogether() {
            int busy = 0;
            int waitingTogether = 0;
            final Map<Object, List<HandlerThread>> parked = new IdentityHashMap<>();
            for (HandlerThread thread : workers.threads) {
                if (!thread.busy) {
                    continue;
                }
                busy++;
                final Thread.State state = thread.getState();
                if (state == Thread.State.BLOCKED) {
                    waitingTogether++;
                } else if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                    final Object blocker = LockSupport.getBlocker(thread);
                    if (blocker != null) {
                        parked.computeIfAbsent(blocker, b -> new ArrayList<>(1)).add(thread);
                    }
                }
            }
            // a lock, a latch or a semaphore: only a lock has an owner, which the JVM knows
            final List<HandlerThread> alone = new ArrayList<>();
            for (Map.Entry<Object, List<HandlerThread>> waiting : parked.entrySet()) {
                if (waiting.getValue().size() > 1) {
                    waitingTogether += waiting.getValue().size();
                } else if (waiting.getKey() instanceof AbstractOwnableSynchronizer) {
                    alone.add(waiting.getValue().get(0));
                }
            }
            if (waitingTogether * 2 < busy && (waitingTogether + alone.size()) * 2 >= busy) {
                waitingTogether += waitingForAHeldLock(alone);
            }
            return busy > 0 && waitingTogether * 2 >= busy;
        }

        /**
         * How many of the threads wait for a lock that some thread holds. Reading their state without their stack
         * does not pause the JVM.
         */
        private static int waitingForAHeldLock(List<HandlerThread> threads) {
            final long[] ids = new long[threads.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = threads.get(i).getId();
            }
            int waiting = 0;
            try {
                for (ThreadInfo info : ManagementFactory.getThreadMXBean().getThreadInfo(ids, 0)) {
                    if (info != null && info.getLockOwnerId() != -1) {
                        waiting++;
                    }
                }
            } catch (SecurityException e) {
                // not allowed to read the threads: they only count when they wait for the same lock
                log.debug("Could not read the locks the handler threads wait for", e);
            }
            return waiting;
        }

        /**
         * Synchronized with {@link #setThreadsLimit(int)}: the core size is raised for a moment while growing, and the
         * limit must never be set below it.
         */
        private synchronized void grow(int waitingTasks) {
            // at most quadruples the pool on every check: it reaches the limit in a few checks when the handlers
            // block, but a pause of the JVM (that makes the queued tasks look stuck) adds just a few threads
            final int poolSize = getPoolSize();
            final int step = Math.min(waitingTasks, Math.max(base, poolSize * 3));
            final int target = Math.min(getMaximumPoolSize(), poolSize + step);
            if (target > poolSize) {
                // the new threads take the waiting tasks, and go away once not needed for the keep-alive period
                growthCount.incrementAndGet();
                setCorePoolSize(target);
                prestartAllCoreThreads();
                setCorePoolSize(base);
            }
        }

        /**
         * Changes the most threads the pool may have. Lowering it does not stop running handlers: the threads above
         * the limit go away once idle.
         */
        synchronized void setThreadsLimit(int maxThreads) {
            if (maxThreads != getMaximumPoolSize()) {
                setMaximumPoolSize(maxThreads);
                base = baseThreads(maxThreads);
                setCorePoolSize(base);
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

        private static int baseThreads(int maxThreads) {
            return Math.min(maxThreads, Math.max(2, Runtime.getRuntime().availableProcessors()));
        }

        long growthCount() {
            return growthCount.get();
        }

        /**
         * The threads the pool started since it was created.
         */
        long startedThreads() {
            return workers.counter.get();
        }
    }

    /**
     * An unbounded lock-free queue that asks the pool to watch for starving tasks every time one is queued, and lets a
     * thread of the pool leave when it asks for a task the pool does not need it for.
     */
    private static final class GrowingQueue extends LinkedTransferQueue<Runnable> {
        private transient volatile ElasticExecutor owner;

        /**
         * Called by a thread of the pool waiting for its next task: no task (null) makes it leave the pool.
         */
        @Override
        public Runnable poll(long timeout, @NonNull TimeUnit unit) throws InterruptedException {
            final ElasticExecutor pool = owner;
            if (pool != null && pool.releaseIfSurplus()) {
                return null;
            }
            return super.poll(timeout, unit);
        }

        @Override
        public boolean offer(@NonNull Runnable task) {
            final boolean queued = super.offer(task);
            final ElasticExecutor pool = owner;
            if (pool != null) {
                pool.scheduleStarvationCheck();
            }
            return queued;
        }
    }

    /**
     * Creates the daemon threads of the handler pool and keeps the live ones, so the pool can see what its busy
     * threads are waiting for.
     */
    private static final class Workers implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();
        private final Set<HandlerThread> threads = ConcurrentHashMap.newKeySet();
        private final AtomicInteger live = new AtomicInteger(); // the threads not released yet

        Workers(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(@NonNull Runnable runnable) {
            final HandlerThread thread = new HandlerThread(runnable, prefix + "-" + counter.incrementAndGet(), this);
            threads.add(thread);
            live.incrementAndGet();
            return thread;
        }
    }

    /**
     * A thread of the handler pool: {@link #busy} is set while it runs a task, {@link #released} once the pool lets it
     * go, and it leaves the live threads when it ends.
     */
    private static final class HandlerThread extends Thread {
        private final Workers workers;
        private volatile boolean busy;
        private volatile boolean released; // already taken off Workers.live

        HandlerThread(Runnable runnable, String name, Workers workers) {
            super(runnable, name);
            this.workers = workers;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                super.run();
            } finally {
                workers.threads.remove(this);
                if (!released) {
                    workers.live.decrementAndGet();
                }
            }
        }
    }
}
