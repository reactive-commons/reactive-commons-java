package org.reactivecommons.async.rabbit.listeners;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ListenerRestartsTest {

    private static final String QUEUE = "app.subsEvents";

    private final Logger log = (Logger) LoggerFactory.getLogger(ListenerRestarts.class);
    private final List<ILoggingEvent> logged = new CopyOnWriteArrayList<>();
    private final AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent event) {
            logged.add(event);
        }
    };
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ListenerLiveness liveness = new ListenerLiveness(QUEUE);
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicInteger subscriptions = new AtomicInteger();
    private Level previousLevel;
    private ListenerRestarts restarts;

    @BeforeEach
    void init() {
        appender.start();
        previousLevel = log.getLevel();
        log.setLevel(Level.DEBUG);
        log.addAppender(appender);
        restarts = new ListenerRestarts("listener for queue " + QUEUE, liveness,
                new ConsumerMetrics(registry, "app", QUEUE, liveness), stopped::get, subscriptions::incrementAndGet);
    }

    @AfterEach
    void detach() {
        log.detachAppender(appender);
        log.setLevel(previousLevel);
    }

    @Test
    void theBackoffDoublesFromTheMinimumUpToTheMaximum() {
        assertThat(ListenerRestarts.backoffMillis(0)).isEqualTo(200);
        assertThat(ListenerRestarts.backoffMillis(1)).isEqualTo(200);
        assertThat(ListenerRestarts.backoffMillis(2)).isEqualTo(400);
        assertThat(ListenerRestarts.backoffMillis(5)).isEqualTo(3_200);
        assertThat(ListenerRestarts.backoffMillis(6)).isEqualTo(5_000);
        assertThat(ListenerRestarts.backoffMillis(Long.MAX_VALUE)).isEqualTo(5_000);
    }

    @Test
    void theTriggersOfARestartAlreadyScheduledSubscribeOnceAndAreCountedOnce() {
        restarts.schedule(ConsumerMetrics.REASON_ERROR);
        restarts.schedule(ConsumerMetrics.REASON_CHANNEL_SHUTDOWN);

        await().atMost(Duration.ofSeconds(2)).until(() -> subscriptions.get() == 1);
        await().during(Duration.ofMillis(300)).until(() -> subscriptions.get() == 1);
        // counted once, with the reason of the first trigger
        assertThat(restartCount(ConsumerMetrics.REASON_ERROR)).isEqualTo(1);
        assertThat(restartCount(ConsumerMetrics.REASON_CHANNEL_SHUTDOWN)).isZero();
    }

    @Test
    void aStoppedListenerIsNotRestarted() {
        stopped.set(true);

        restarts.schedule(ConsumerMetrics.REASON_CANCEL);

        await().during(Duration.ofMillis(400)).until(() -> subscriptions.get() == 0);
        assertThat(logged).isEmpty();
    }

    @Test
    void onlyTheFirstRestartIsLoggedAtWarn() {
        restarts.schedule(ConsumerMetrics.REASON_ERROR);
        await().atMost(Duration.ofSeconds(2)).until(() -> subscriptions.get() == 1);
        restarts.schedule(ConsumerMetrics.REASON_ERROR);
        await().atMost(Duration.ofSeconds(2)).until(() -> subscriptions.get() == 2);

        assertThat(logged).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.DEBUG);
        assertThat(logged.get(0).getFormattedMessage())
                .isEqualTo("Re-subscribing listener for queue " + QUEUE + " in 200 ms (attempt 1)");
    }

    private double restartCount(String reason) {
        Counter counter = registry.find("reactive_commons.consumer.restart").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }
}
