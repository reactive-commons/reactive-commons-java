package org.reactivecommons.async.rabbit.listeners;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class ListenerSchedulersTest {

    @BeforeEach
    void setUp() {
        ListenerSchedulers.release(); // other tests of the JVM may have left a pool that grew
        ListenerSchedulers.retain();
    }

    @AfterEach
    void tearDown() {
        ListenerSchedulers.release();
    }

    @Test
    void shouldShareTheSameSchedulersBetweenCallers() {
        assertThat(ListenerSchedulers.handlers()).isSameAs(ListenerSchedulers.handlers());
        assertThat(ListenerSchedulers.errorReporter()).isSameAs(ListenerSchedulers.errorReporter());
    }

    @Test
    void shouldRunBlockingHandlersAtTheSameTimeUpToTheLimit() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        int total = ListenerSchedulers.MAX_HANDLER_THREADS + 50;

        Flux.range(0, total)
                .flatMap(i -> Mono.fromRunnable(() -> {
                    peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                    sleep(200);
                    running.decrementAndGet();
                }).subscribeOn(ListenerSchedulers.handlers()), total)
                .blockLast(Duration.ofSeconds(30));

        assertThat(peak.get()).as("handlers running at the same time")
                .isBetween(ListenerSchedulers.MAX_HANDLER_THREADS - 20, ListenerSchedulers.MAX_HANDLER_THREADS);
    }

    /**
     * Every listener can handle its maxConcurrency deliveries at the same time: the limit of the shared pool fits all
     * of them, so the handlers of one listener never wait for the threads taken by another.
     */
    @Test
    void theLimitFitsTheConcurrencyOfEveryListener() {
        ListenerSchedulers.handlers();
        assertThat(ListenerSchedulers.handlerThreadsLimit()).isEqualTo(ListenerSchedulers.MAX_HANDLER_THREADS);

        ListenerSchedulers.registerListener(250);
        ListenerSchedulers.registerListener(250);
        ListenerSchedulers.registerListener(100);
        assertThat(ListenerSchedulers.handlerThreadsLimit()).isEqualTo(600);

        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Flux.range(0, 600)
                .flatMap(i -> Mono.fromRunnable(() -> {
                    peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                    sleep(300);
                    running.decrementAndGet();
                }).subscribeOn(ListenerSchedulers.handlers()), 600)
                .blockLast(Duration.ofSeconds(30));
        assertThat(peak.get()).as("handlers running at the same time").isGreaterThan(ListenerSchedulers.MAX_HANDLER_THREADS);

        ListenerSchedulers.unregisterListener(250);
        ListenerSchedulers.unregisterListener(250);
        ListenerSchedulers.unregisterListener(100);
        assertThat(ListenerSchedulers.handlerThreadsLimit()).isEqualTo(ListenerSchedulers.MAX_HANDLER_THREADS);
    }

    @Test
    void shouldNotGrowWithABurstOfTasksThatFinishQuickly() {
        Set<String> threads = ConcurrentHashMap.newKeySet();

        // 40000 tasks submitted with the concurrency of the listeners: the backlog is large but drains in microseconds
        Flux.range(0, 40_000)
                .flatMap(i -> Mono.fromRunnable(() -> threads.add(Thread.currentThread().getName()))
                        .subscribeOn(ListenerSchedulers.handlers()), ListenerSchedulers.MAX_HANDLER_THREADS)
                .blockLast(Duration.ofSeconds(30));

        assertThat(threads).as("threads used by a burst of tasks that do not block")
                .hasSizeLessThanOrEqualTo(Runtime.getRuntime().availableProcessors() * 4) // 250 when it grows without need
                .allMatch(name -> name.startsWith("rc-handlers-"));
    }

    @Test
    void shouldQueueTheTasksBeyondTheLimitInsteadOfRejectingThem() throws InterruptedException {
        int total = ListenerSchedulers.MAX_HANDLER_THREADS * 2;
        CountDownLatch done = new CountDownLatch(total);
        Scheduler handlers = ListenerSchedulers.handlers();

        for (int i = 0; i < total; i++) {
            Mono.fromRunnable(() -> {
                sleep(50);
                done.countDown();
            }).subscribeOn(handlers).subscribe();
        }

        assertThat(done.await(30, TimeUnit.SECONDS)).as("every task ran").isTrue();
    }

    @Test
    void shouldDisposeTheSchedulersWhenTheLastUserReleasesThemAndCreateNewOnesIfUsedAgain() {
        Scheduler first = ListenerSchedulers.handlers();
        Scheduler firstReporter = ListenerSchedulers.errorReporter();
        Mono.fromRunnable(() -> { }).subscribeOn(first).block(Duration.ofSeconds(5));

        ListenerSchedulers.release(); // the last user: disposed
        assertThat(firstReporter.isDisposed()).isTrue();
        ListenerSchedulers.retain(); // balances tearDown

        Scheduler second = ListenerSchedulers.handlers();
        assertThat(second).isNotSameAs(first);
        assertThat(Mono.just("ok").subscribeOn(second).block(Duration.ofSeconds(5))).isEqualTo("ok");
        assertThat(ListenerSchedulers.errorReporter()).isNotSameAs(firstReporter);
    }

    @Test
    void shouldKeepTheSchedulersWhileAnotherUserIsStillUsingThem() {
        Scheduler handlers = ListenerSchedulers.handlers();
        ListenerSchedulers.retain(); // a second application context

        ListenerSchedulers.release(); // the second one closes

        assertThat(ListenerSchedulers.handlers()).isSameAs(handlers);
        assertThat(Mono.just("ok").subscribeOn(handlers).block(Duration.ofSeconds(5))).isEqualTo("ok");
    }

    @Test
    void shouldLetTheRunningHandlersFinishWhenReleased() throws InterruptedException {
        AtomicInteger finished = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        Mono.fromRunnable(() -> {
            started.countDown();
            sleep(500);
            finished.incrementAndGet();
        }).subscribeOn(ListenerSchedulers.handlers()).subscribe();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        ListenerSchedulers.release();
        ListenerSchedulers.retain(); // balances tearDown

        assertThat(finished.get()).as("the running handler finished before the release returned").isEqualTo(1);
    }

    @Test
    void shouldRejectTasksAfterTheExecutorIsShutDown() {
        Scheduler first = ListenerSchedulers.handlers();
        ListenerSchedulers.release();
        ListenerSchedulers.retain(); // balances tearDown

        assertThatThrownBy(() -> Mono.just(1).subscribeOn(first).block(Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldRegisterThePoolMetersWhileAnApplicationWantsThemAndRemoveThemOnRelease() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ListenerSchedulers.retain(registry);
        try {
            ListenerSchedulers.handlers(); // creates the pool, then registers its gauges

            assertThat(registry.find("reactive_commons.schedulers.handler.pool.size").gauge()).isNotNull();
            assertThat(registry.find("reactive_commons.schedulers.handler.threads.active").gauge()).isNotNull();
            assertThat(registry.find("reactive_commons.schedulers.handler.queue.size").gauge()).isNotNull();
            assertThat(registry.find("reactive_commons.schedulers.handler.growth").functionCounter()).isNotNull();
        } finally {
            ListenerSchedulers.release(registry);
        }

        assertThat(registry.find("reactive_commons.schedulers.handler.pool.size").gauge()).isNull();
        assertThat(registry.find("reactive_commons.schedulers.handler.threads.active").gauge()).isNull();
        assertThat(registry.find("reactive_commons.schedulers.handler.queue.size").gauge()).isNull();
        assertThat(registry.find("reactive_commons.schedulers.handler.growth").functionCounter()).isNull();
    }

    @Test
    void shouldRemoveTheMetersOfTheReleasedRegistryOnly() {
        SimpleMeterRegistry first = new SimpleMeterRegistry();
        SimpleMeterRegistry second = new SimpleMeterRegistry();
        ListenerSchedulers.retain(first);
        ListenerSchedulers.retain(second);
        try {
            ListenerSchedulers.handlers();
            ListenerSchedulers.release(first);

            assertThat(first.find("reactive_commons.schedulers.handler.pool.size").gauge()).isNull();
            assertThat(second.find("reactive_commons.schedulers.handler.pool.size").gauge()).isNotNull();
        } finally {
            ListenerSchedulers.release(second);
        }
    }

    @Test
    void shouldKeepTheMetersOfARegistryWhileAnotherApplicationRetainsIt() {
        SimpleMeterRegistry shared = new SimpleMeterRegistry(); // e.g. the registry of a parent context
        ListenerSchedulers.retain(shared);
        ListenerSchedulers.retain(shared);
        try {
            ListenerSchedulers.handlers();
            ListenerSchedulers.release(shared);

            assertThat(shared.find("reactive_commons.schedulers.handler.pool.size").gauge())
                    .as("the other application still uses the registry").isNotNull();
        } finally {
            ListenerSchedulers.release(shared);
        }

        assertThat(shared.find("reactive_commons.schedulers.handler.pool.size").gauge()).isNull();
    }

    @Test
    void shouldCountTheTimesThePoolGrewBecauseItsTasksWereStarving() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ListenerSchedulers.retain(registry);
        try {
            Scheduler handlers = ListenerSchedulers.handlers();
            // more blocking tasks than threads (whatever the core count): the queued head starves and the pool grows
            Flux.range(0, 400)
                    .flatMap(i -> Mono.fromRunnable(() -> sleep(20)).subscribeOn(handlers), 400)
                    .blockLast(Duration.ofSeconds(30));

            FunctionCounter growth = registry.find("reactive_commons.schedulers.handler.growth").functionCounter();
            assertThat(growth).isNotNull();
            assertThat(growth.count()).as("the pool grew at least once").isPositive();
        } finally {
            ListenerSchedulers.release(registry);
        }
    }

    /**
     * Handlers that log through a synchronous appender all wait for its monitor while one thread writes: more threads
     * would only queue up behind it, so the pool keeps its base size although the tasks starve.
     */
    @Test
    void shouldNotGrowWhileTheBusyThreadsWaitForTheSameMonitor() {
        Object appender = new Object();
        Set<String> threads = ConcurrentHashMap.newKeySet();

        Flux.range(0, 150)
                .flatMap(i -> Mono.fromRunnable(() -> {
                    threads.add(Thread.currentThread().getName());
                    synchronized (appender) {
                        sleep(10);
                    }
                }).subscribeOn(ListenerSchedulers.handlers()), 150)
                .blockLast(Duration.ofSeconds(30));

        assertThat(threads).as("threads used while every handler waits for the same monitor")
                .hasSizeLessThanOrEqualTo(baseThreads());
    }

    /**
     * The same with an appender that uses a lock instead of a monitor, as Logback does. With two threads (one or two
     * processors) one writes while the other waits alone for the lock.
     */
    @Test
    void shouldNotGrowWhileTheBusyThreadsWaitForTheSameLock() {
        ReentrantLock appender = new ReentrantLock();
        Set<String> threads = ConcurrentHashMap.newKeySet();

        Flux.range(0, 150)
                .flatMap(i -> Mono.fromRunnable(() -> {
                    threads.add(Thread.currentThread().getName());
                    appender.lock();
                    try {
                        sleep(10);
                    } finally {
                        appender.unlock();
                    }
                }).subscribeOn(ListenerSchedulers.handlers()), 150)
                .blockLast(Duration.ofSeconds(30));

        assertThat(threads).as("threads used while every handler waits for the same lock")
                .hasSizeLessThanOrEqualTo(baseThreads());
    }

    /**
     * One handler writes to an appender while the other waits for its lock: no other handler waits for that lock,
     * but another thread holds it, so both wait for a shared resource.
     */
    @Test
    void aHandlerWaitingAloneForALockAnotherThreadHoldsWaitsForASharedResource() {
        ReentrantLock appender = new ReentrantLock();
        CountDownLatch written = new CountDownLatch(1);

        boolean together = busyThreadsWaitTogether(() -> {
            appender.lock();
            try {
                waitFor(written); // writing
            } finally {
                appender.unlock();
            }
        }, () -> {
            appender.lock();
            appender.unlock();
        });

        assertThat(together).as("a handler writes, the other waits for the lock it holds").isTrue();
    }

    /**
     * A handler parked on a latch of its own (a blocking client call, {@code Mono#block}) waits for its own work: a
     * latch has no owner, unlike a lock.
     */
    @Test
    void handlersParkedOnLatchesOfTheirOwnWaitForTheirOwnWork() {
        CountDownLatch firstReply = new CountDownLatch(1);
        CountDownLatch secondReply = new CountDownLatch(1);

        boolean together = busyThreadsWaitTogether(() -> waitFor(firstReply), () -> waitFor(secondReply));

        assertThat(together).as("each handler waits for its own reply").isFalse();
    }

    /**
     * Runs the tasks on a pool of as many threads, each once the previous one is parked, and tells whether its busy
     * threads wait together then. The pool is at its limit, so it does not grow meanwhile.
     */
    private static boolean busyThreadsWaitTogether(Runnable... tasks) {
        ListenerSchedulers.ElasticExecutor pool = new ListenerSchedulers.ElasticExecutor(
                tasks.length, "sampled", KEEP_ALIVE, System::nanoTime);
        try {
            for (Runnable task : tasks) {
                AtomicReference<Thread> running = new AtomicReference<>();
                pool.execute(() -> {
                    running.set(Thread.currentThread());
                    task.run();
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((running.get() == null || LockSupport.getBlocker(running.get()) == null)
                        && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertThat(running.get()).as("the task parked").isNotNull()
                        .extracting(LockSupport::getBlocker).isNotNull();
            }
            return pool.busyThreadsWaitTogether();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Handlers that each wait for their own result (e.g. a blocking client call) are parked on different objects: the
     * pool grows so they can wait at the same time.
     */
    @Test
    void shouldGrowWhileEachBusyThreadWaitsForItsOwnResult() {
        ScheduledExecutorService replies = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        try {
            Flux.range(0, 300)
                    .flatMap(i -> Mono.fromRunnable(() -> {
                        peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                        CompletableFuture<Void> reply = new CompletableFuture<>();
                        replies.schedule(() -> reply.complete(null), 200, TimeUnit.MILLISECONDS);
                        reply.join();
                        running.decrementAndGet();
                    }).subscribeOn(ListenerSchedulers.handlers()), 300)
                    .blockLast(Duration.ofSeconds(30));
        } finally {
            replies.shutdownNow();
        }

        assertThat(peak.get()).as("handlers waiting for their own result at the same time")
                .isGreaterThan(baseThreads() * 4);
    }

    /**
     * A pause of the process (a GC pause, the CPU quota of the container used up) freezes the handlers and the monitor
     * of the pool alike: the check that runs after it finds the same task at the head of the queue, although nobody
     * could take it. Such a late check does not make the pool grow, while the same tasks on time do.
     */
    @Test
    void aStarvationCheckDelayedByAPauseOfTheProcessDoesNotGrowThePool() throws InterruptedException {
        AtomicLong reads = new AtomicLong();
        // every read of the clock is 50 ms after the previous one: every check runs late, as after a pause
        LongSupplier pausing = () -> System.nanoTime() + reads.incrementAndGet() * 50_000_000L;
        ListenerSchedulers.ElasticExecutor paused = new ListenerSchedulers.ElasticExecutor(
                ListenerSchedulers.MAX_HANDLER_THREADS, "paused", KEEP_ALIVE, pausing);
        ListenerSchedulers.ElasticExecutor onTime = new ListenerSchedulers.ElasticExecutor(
                ListenerSchedulers.MAX_HANDLER_THREADS, "on-time", KEEP_ALIVE, System::nanoTime);
        try {
            int pausedPeak = runTasksWaitingForTheirOwnResult(paused, 60, 100);
            int onTimePeak = runTasksWaitingForTheirOwnResult(onTime, 60, 100);

            assertThat(pausedPeak).as("handlers at the same time when every check runs late")
                    .isLessThanOrEqualTo(baseThreads());
            assertThat(onTimePeak).as("handlers at the same time when the checks run on time")
                    .isGreaterThan(baseThreads());
        } finally {
            paused.shutdownNow();
            onTime.shutdownNow();
        }
    }

    /**
     * Under a steady flow of tasks the idle threads take them in turn, so none stays idle for the keep-alive time:
     * the threads the handlers stopped needing are released anyway, once a whole keep-alive period passed without
     * needing them.
     */
    @Test
    void theThreadsNoLongerNeededAreReleasedWhileTasksKeepComing() throws InterruptedException {
        AtomicLong offset = new AtomicLong();
        ListenerSchedulers.ElasticExecutor pool = new ListenerSchedulers.ElasticExecutor(
                ListenerSchedulers.MAX_HANDLER_THREADS, "shrinking", KEEP_ALIVE, () -> System.nanoTime() + offset.get());
        try {
            runTasksWaitingForTheirOwnResult(pool, 200, 200);
            int grown = pool.getPoolSize();
            assertThat(grown).as("threads after handlers that block").isGreaterThan(baseThreads() * 2);

            offset.addAndGet(KEEP_ALIVE.toNanos()); // the period of the blocking handlers ends
            runShortTasksOneAtATime(pool, 500);
            assertThat(pool.getPoolSize()).as("threads kept: the handlers needed them in the previous period")
                    .isGreaterThanOrEqualTo(grown);

            offset.addAndGet(KEEP_ALIVE.toNanos()); // a whole period of short tasks, one at a time
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pool.getPoolSize() > baseThreads() && System.nanoTime() < deadline) {
                runShortTasksOneAtATime(pool, 50);
            }
            assertThat(pool.getPoolSize()).as("threads once the handlers stopped needing them")
                    .isEqualTo(baseThreads());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The pool grows by starting the threads the starving tasks need. The first new threads drain the queue, and the
     * rest find it empty: they must not be released as surplus while the pool grows, or it never reaches the size it
     * grows to and keeps starting threads that leave right away (hundreds per second, in the 7.8.0-1-HC benchmark).
     */
    @Test
    void growingThePoolDoesNotStartThreadsThatLeaveRightAway() throws InterruptedException {
        ListenerSchedulers.ElasticExecutor pool = new ListenerSchedulers.ElasticExecutor(
                ListenerSchedulers.MAX_HANDLER_THREADS, "growing", KEEP_ALIVE, System::nanoTime);
        ScheduledExecutorService replies = Executors.newSingleThreadScheduledExecutor();
        try {
            for (int round = 0; round < 5; round++) {
                // every thread of the pool waits for its own result, then short tasks queue up: the pool grows
                CountDownLatch blocked = new CountDownLatch(baseThreads());
                for (int i = 0; i < baseThreads(); i++) {
                    pool.execute(() -> {
                        CompletableFuture<Void> reply = new CompletableFuture<>();
                        replies.schedule(() -> reply.complete(null), 100, TimeUnit.MILLISECONDS);
                        reply.join();
                        blocked.countDown();
                    });
                }
                runShortTasksAtOnce(pool, 200);
                assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
            }
            long startedBefore = pool.startedThreads();

            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(pool.startedThreads() - startedBefore)
                            .as("threads started once no task is waiting").isZero());
            assertThat(pool.getCorePoolSize()).as("core size once the pool grew").isEqualTo(baseThreads());
        } finally {
            replies.shutdownNow();
            pool.shutdownNow();
        }
    }

    private static void runShortTasksAtOnce(ListenerSchedulers.ElasticExecutor pool, int tasks)
            throws InterruptedException {
        CountDownLatch done = new CountDownLatch(tasks);
        for (int i = 0; i < tasks; i++) {
            pool.execute(done::countDown);
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).as("every short task ran").isTrue();
    }

    /**
     * Handlers that wait for each other (here a latch that only enough handlers running at the same time open) all
     * wait for the same blocker, as handlers behind a shared lock do: but no task completes, so the pool grows instead
     * of leaving them waiting forever for tasks that stay queued.
     */
    @Test
    void handlersWaitingForTasksStillQueuedMakeThePoolGrow() throws InterruptedException {
        ListenerSchedulers.ElasticExecutor pool = new ListenerSchedulers.ElasticExecutor(
                ListenerSchedulers.MAX_HANDLER_THREADS, "barrier", KEEP_ALIVE, System::nanoTime);
        int handlers = baseThreads() * 3;
        CountDownLatch barrier = new CountDownLatch(handlers);
        CountDownLatch done = new CountDownLatch(handlers);
        try {
            for (int i = 0; i < handlers; i++) {
                pool.execute(() -> {
                    barrier.countDown();
                    try {
                        if (barrier.await(30, TimeUnit.SECONDS)) {
                            done.countDown();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertThat(done.await(15, TimeUnit.SECONDS)).as("every handler got past the barrier").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    private static final Duration KEEP_ALIVE = Duration.ofSeconds(15);

    /**
     * Runs the tasks at the same time, each waiting for its own result (as a blocking client call), and returns the
     * most that ran at the same time.
     */
    private static int runTasksWaitingForTheirOwnResult(ListenerSchedulers.ElasticExecutor pool, int tasks,
                                                        long millis) throws InterruptedException {
        ScheduledExecutorService replies = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(tasks);
        try {
            for (int i = 0; i < tasks; i++) {
                pool.execute(() -> {
                    peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                    CompletableFuture<Void> reply = new CompletableFuture<>();
                    replies.schedule(() -> reply.complete(null), millis, TimeUnit.MILLISECONDS);
                    reply.join();
                    running.decrementAndGet();
                    done.countDown();
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).as("every task ran").isTrue();
        } finally {
            replies.shutdownNow();
        }
        return peak.get();
    }

    /**
     * Each task starts once the previous one is over, its thread included (it counts as busy until then): one handler
     * at a time, whatever the number of processors.
     */
    private static void runShortTasksOneAtATime(ListenerSchedulers.ElasticExecutor pool, int tasks)
            throws InterruptedException {
        for (int i = 0; i < tasks; i++) {
            CountDownLatch done = new CountDownLatch(1);
            pool.execute(done::countDown);
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pool.getActiveCount() > 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }
    }

    private static int baseThreads() {
        return Math.min(ListenerSchedulers.MAX_HANDLER_THREADS, Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
