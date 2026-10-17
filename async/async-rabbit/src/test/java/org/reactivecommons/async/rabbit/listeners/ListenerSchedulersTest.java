package org.reactivecommons.async.rabbit.listeners;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
