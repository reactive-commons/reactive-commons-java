package org.reactivecommons.async.rabbit.communications;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.exceptions.SendConfirmUnknownException;
import org.reactivecommons.async.commons.exceptions.SendFailureNoAckException;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.SendOptions;
import reactor.rabbitmq.Sender;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The send timeout of a publish is not a timer per message: the messages waiting for their outcome are checked all
 * at once, every {@code sendTimeout / 20} (between 10 and 100 ms). Only the waits without a pending message, an
 * exchange declaration in progress or no publishing flow, keep a timer of their own.
 */
@SuppressWarnings("unchecked")
class ReactiveMessageSenderTimeoutTest {

    private static final Duration SEND_TIMEOUT = Duration.ofMillis(300);
    /**
     * The check runs every 15 ms with this timeout: a generous margin for a slow runner.
     */
    private static final Duration LATENESS = Duration.ofMillis(500);
    private static final String SCHEDULE_HOOK = "count-timers";
    /**
     * The most a test waits for the outcome of a publish.
     */
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final Sender sender = mock(Sender.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private ReactiveMessageSender messageSender;

    @AfterEach
    void close() {
        Schedulers.resetOnScheduleHook(SCHEDULE_HOOK);
        if (messageSender != null) {
            messageSender.close();
        }
    }

    @Test
    void aPublishThatSettlesInTimeSchedulesNoTimer() {
        confirmEveryMessage();
        messageSender = newSender(sender, null, ReactiveMessageSender.DEFAULT_SEND_TIMEOUT);
        messageSender.sendWithConfirm("warm-up", "exchange", "rkey", new HashMap<>(), true).block();
        Thread caller = Thread.currentThread();
        AtomicInteger timers = new AtomicInteger();
        Schedulers.onScheduleHook(SCHEDULE_HOOK, task -> {
            if (Thread.currentThread() == caller) { // not the tasks of other tests still running
                timers.incrementAndGet();
            }
            return task;
        });

        for (int i = 0; i < 200; i++) {
            messageSender.sendWithConfirm("message-" + i, "exchange", "rkey", new HashMap<>(), true)
                    .block(WAIT);
            messageSender.sendNoConfirm("message-" + i, "exchange", "rkey", new HashMap<>(), true)
                    .block(WAIT);
        }

        assertThat(timers).as("timers scheduled by 400 publishes").hasValue(0);
        assertThat(latencies("ack", true)).isEqualTo(201);
        assertThat(latencies("published", false)).isEqualTo(200);
    }

    @Test
    void aWrittenMessageNeverConfirmedFailsWithAnUnknownOutcomeJustAfterItsSendTimeout() {
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            Flux<OutboundMessage> messages = invocation.getArgument(0);
            messages.subscribe(); // written, never confirmed
            return Flux.never();
        });
        lenient().when(sender.send(any(Publisher.class), any(SendOptions.class))).thenReturn(Mono.never());
        messageSender = newSender(sender, null, SEND_TIMEOUT);

        long start = System.nanoTime();
        Mono<Void> send = messageSender.sendWithConfirm("silent", "exchange", "rkey", new HashMap<>(), true);
        assertThatThrownBy(() -> send.block(WAIT))
                .isExactlyInstanceOf(SendConfirmUnknownException.class)
                .hasMessageStartingWith("No confirmation received within " + SEND_TIMEOUT)
                .hasCauseInstanceOf(TimeoutException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isBetween(SEND_TIMEOUT, SEND_TIMEOUT.plus(LATENESS));
        assertThat(outcomes("timeout", true)).as("outcome counted once").isEqualTo(1);
        assertThat(latencies("timeout", true)).as("latency recorded once").isEqualTo(1);
    }

    @Test
    void aPublishWaitingForAnExchangeDeclarationThatNeverEndsFailsWithinItsSendTimeout() {
        confirmEveryMessage();
        TopologyCreator topologyCreator = mock(TopologyCreator.class);
        when(topologyCreator.declare(any(ExchangeSpecification.class))).thenReturn(Mono.never());
        messageSender = newSender(sender, topologyCreator, SEND_TIMEOUT);
        messageSender.declareExchange(ExchangeSpecification.exchange("exchange"));

        long start = System.nanoTime();
        Mono<Void> send = messageSender.sendWithConfirm("waiting", "exchange", "rkey", new HashMap<>(), true);
        assertThatThrownBy(() -> send.block(WAIT))
                .isExactlyInstanceOf(SendFailureNoAckException.class)
                .hasMessageContaining("no RabbitMQ publishing flow available within " + SEND_TIMEOUT);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isBetween(SEND_TIMEOUT, SEND_TIMEOUT.plus(LATENESS));
        assertThat(outcomes("timeout", true)).isEqualTo(1);
        assertThat(latencies("timeout", true)).isEqualTo(1);
    }

    @Test
    void noTimeoutCheckStaysScheduledOnceNoMessageIsPending() throws Exception {
        confirmEveryMessage();
        messageSender = newSender(sender, null, SEND_TIMEOUT);

        messageSender.sendWithConfirm("message", "exchange", "rkey", new HashMap<>(), true)
                .block(WAIT);

        Field field = ReactiveMessageSender.class.getDeclaredField("timeoutChecker");
        field.setAccessible(true);
        ScheduledThreadPoolExecutor checker = (ScheduledThreadPoolExecutor) field.get(messageSender);
        await().atMost(Duration.ofSeconds(2)).until(() -> checker.getQueue().isEmpty());
        assertThat(checker.allowsCoreThreadTimeOut()).as("its idle thread is released").isTrue();
    }

    private void confirmEveryMessage() {
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            Flux<OutboundMessage> messages = invocation.getArgument(0);
            return messages.map(message -> new OutboundMessageResult<>(message, true));
        });
        lenient().when(sender.send(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            Flux<OutboundMessage> messages = invocation.getArgument(0);
            return messages.doOnNext(ReactiveMessageSenderTimeoutTest::written).then();
        });
    }

    /**
     * What the no-confirm flow of the RabbitMQ client does once it wrote the message to the channel.
     */
    private static void written(OutboundMessage message) {
        try {
            Method published = OutboundMessage.class.getDeclaredMethod("published");
            published.setAccessible(true);
            published.invoke(message);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private ReactiveMessageSender newSender(Sender sender, TopologyCreator topologyCreator, Duration sendTimeout) {
        return new ReactiveMessageSender(sender, "TestApp", new RabbitJacksonMessageConverter(new JsonMapper()),
                topologyCreator, null, new SenderSettings(false, sendTimeout, 1024,
                ReactiveMessageSender.DEFAULT_MAX_MESSAGE_SIZE, new ConnectionBlockedState(), registry, "test"));
    }

    private double outcomes(String outcome, boolean confirm) {
        return registry.find("reactive_commons.publish.outcome")
                .tags("outcome", outcome, "confirm", Boolean.toString(confirm)).counter().count();
    }

    private long latencies(String outcome, boolean confirm) {
        return registry.find("reactive_commons.publish.latency")
                .tags("outcome", outcome, "confirm", Boolean.toString(confirm)).timer().count();
    }
}
