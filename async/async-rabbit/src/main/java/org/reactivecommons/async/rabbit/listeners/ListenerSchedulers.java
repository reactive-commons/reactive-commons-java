package org.reactivecommons.async.rabbit.listeners;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.java.Log;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * The schedulers shared by every listener of the application, instead of a pair of pools per listener.
 *
 * <ul>
 *     <li>{@link #handlers()} runs the handlers. It is a pool of up to {@value #MAX_HANDLER_THREADS} threads (the
 *     default {@code maxConcurrency} and {@code prefetchCount}) that starts with one thread per processor and only
 *     grows when tasks have been waiting for more than {@value #MAX_WAIT_MILLIS_BEFORE_GROWING} ms, and whose idle
 *     threads are released after a minute, so handlers that do not
 *     block use a few threads and handlers that block can run at the same time up to the limit.</li>
 *     <li>{@link #errorReporter()} runs the custom error reporter.</li>
 * </ul>
 * They are created when first used, and disposed when the last application using them is released (see
 * {@link #retain()} and {@link #release()}); they are created again if used afterwards, e.g. by another application
 * context in the same JVM.
 */
@Log
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ListenerSchedulers {

    public static final int MAX_HANDLER_THREADS = 250;
    private static final int KEEP_ALIVE_SECONDS = 60;
    private static final long MAX_WAIT_MILLIS_BEFORE_GROWING = 20;
    private static final int ERROR_REPORTER_THREADS = 4;
    private static final int ERROR_REPORTER_QUEUE = 256;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    private static final Object LOCK = new Object();
    private static ElasticExecutor executor; // guarded by LOCK
    private static volatile Scheduler handlers;
    private static volatile Scheduler errorReporter;
    private static int users; // guarded by LOCK

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
        synchronized (LOCK) {
            users++;
        }
    }

    /**
     * Unregisters an application. When the last one is gone the schedulers are disposed: the handlers already
     * submitted are allowed to finish (up to {@value #SHUTDOWN_TIMEOUT_SECONDS} seconds).
     */
    public static void release() {
        ElasticExecutor toStop;
        Scheduler reporter;
        synchronized (LOCK) {
            if (users > 0) {
                users--;
            }
            if (users > 0) {
                return;
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
     * A pool with a small base size (one thread per processor) that only grows, up to its limit, when tasks are
     * starving in its queue: unlike the default {@link ThreadPoolExecutor}, it does not wait for a bounded queue to
     * be full, and once it reached its limit it keeps queueing. Handlers that do not block never leave the base size,
     * handlers that block make it grow.
     */
    private static final class ElasticExecutor extends ThreadPoolExecutor {

        private final ScheduledThreadPoolExecutor monitor;
        private final AtomicBoolean checkScheduled = new AtomicBoolean();

        ElasticExecutor(int maxThreads, ThreadFactory threadFactory) {
            super(baseThreads(maxThreads), maxThreads, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                    new TaskQueue(), threadFactory, new QueueWhenFull());
            ((TaskQueue) getQueue()).owner = this;
            allowCoreThreadTimeOut(true);
            monitor = new ScheduledThreadPoolExecutor(1, daemonThreads("rc-handlers-monitor"));
            monitor.setKeepAliveTime(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
            monitor.allowCoreThreadTimeOut(true);
        }

        @Override
        public void execute(Runnable command) {
            super.execute(new TimedTask(command));
        }

        @Override
        public void shutdown() {
            monitor.shutdownNow();
            super.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            monitor.shutdownNow();
            return super.shutdownNow();
        }

        /**
         * Checks, after {@value ListenerSchedulers#MAX_WAIT_MILLIS_BEFORE_GROWING} ms, if the tasks that were queued are still waiting.
         * It is only scheduled while there are tasks in the queue (at most once at a time), so an idle pool costs
         * nothing.
         */
        void scheduleStarvationCheck() {
            if (!isShutdown() && checkScheduled.compareAndSet(false, true)) {
                try {
                    monitor.schedule(this::growIfStarving, MAX_WAIT_MILLIS_BEFORE_GROWING, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    checkScheduled.set(false); // shut down meanwhile
                }
            }
        }

        private void growIfStarving() {
            checkScheduled.set(false);
            final TaskQueue queue = (TaskQueue) getQueue();
            if (queue.oldestTaskIsStarving()) {
                // at most doubles the pool on every check: it reaches the limit in a few checks when the handlers
                // block, but a pause of the JVM (that makes the queued tasks look old) adds just a few threads
                final int step = Math.min(queue.size(), Math.max(baseThreads(getMaximumPoolSize()), getPoolSize()));
                final int target = Math.min(getMaximumPoolSize(), getPoolSize() + step);
                if (target > getPoolSize()) {
                    // the new threads take the waiting tasks, and go away when idle for a minute
                    setCorePoolSize(target);
                    prestartAllCoreThreads();
                    setCorePoolSize(baseThreads(getMaximumPoolSize()));
                }
            }
            if (!queue.isEmpty()) {
                scheduleStarvationCheck();
            }
        }

        private static int baseThreads(int maxThreads) {
            return Math.min(maxThreads, Math.max(2, Runtime.getRuntime().availableProcessors()));
        }
    }

    /**
     * A task that remembers when it was submitted, to know for how long it has been waiting.
     */
    private static final class TimedTask implements Runnable {
        private final Runnable delegate;
        private final long submittedAt = System.nanoTime();

        TimedTask(Runnable delegate) {
            this.delegate = delegate;
        }

        long waitedNanos() {
            return System.nanoTime() - submittedAt;
        }

        @Override
        public void run() {
            delegate.run();
        }
    }

    /**
     * Makes the pool grow only when the oldest waiting task has been waiting for longer than
     * {@value ListenerSchedulers#MAX_WAIT_MILLIS_BEFORE_GROWING} ms, which means the threads are really busy (blocking handlers).
     * A burst of tasks that finish in microseconds is drained by the base threads before that, so it does not
     * create threads.
     */
    private static final class TaskQueue extends LinkedBlockingQueue<Runnable> {
        private transient volatile ElasticExecutor owner;

        @Override
        public boolean offer(Runnable task) {
            final ElasticExecutor pool = owner;
            if (pool == null || pool.getPoolSize() >= pool.getMaximumPoolSize() || !oldestTaskIsStarving()) {
                final boolean queued = super.offer(task); // it will be taken soon, or the pool cannot grow
                if (queued && pool != null) {
                    pool.scheduleStarvationCheck(); // growing is also needed if nobody offers anything else
                }
                return queued;
            }
            return false; // tasks are starving: let the pool create a thread
        }

        boolean oldestTaskIsStarving() {
            final Runnable oldest = peek();
            return oldest instanceof TimedTask timed
                    && timed.waitedNanos() >= TimeUnit.MILLISECONDS.toNanos(MAX_WAIT_MILLIS_BEFORE_GROWING);
        }

        boolean force(Runnable task) {
            return super.offer(task);
        }
    }

    private static final class QueueWhenFull implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor pool) {
            if (pool.isShutdown() || !((TaskQueue) pool.getQueue()).force(task)) {
                throw new RejectedExecutionException("Handlers executor is shut down");
            }
        }
    }
}
