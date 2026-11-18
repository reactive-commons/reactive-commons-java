package org.reactivecommons.async.kafka.listeners;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOffset;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A listener whose flow terminates (a consumer that cannot be created, a failed poll or commit) subscribes it again
 * with an exponential backoff between 200 ms and 5 s, on a timer, never from the termination itself. Runs in virtual
 * time.
 */
@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
class GenericMessageListenerRestartTest {

    private static final long MIN_BACKOFF_MS = 200;
    private static final long MAX_BACKOFF_MS = 5000;

    @Mock
    private ReactiveMessageListener receiver;
    @Mock
    private TopologyCreator topologyCreator;

    private VirtualTimeScheduler time;
    private final List<Long> subscribedAt = new CopyOnWriteArrayList<>();
    private GenericMessageListener listener;

    @BeforeEach
    void setUp() {
        // Installed before the listener is created, so its threads and its timers run in virtual time
        time = VirtualTimeScheduler.getOrSet();
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.stop();
        }
        VirtualTimeScheduler.reset();
    }

    @Test
    void shouldSubscribeAFailingFlowAgainWithABackoffBetween200MsAnd5sWithoutRecursion() {
        // The consumer cannot even be created (e.g. a property the client rejects): the subscription fails at once,
        // so subscribing again from the termination itself would recurse until the stack overflows
        givenSubscriptions(subscription -> {
            throw new KafkaException("Failed to construct kafka consumer");
        });
        listener = listener(message -> Mono.empty());

        listener.startListener(topologyCreator);
        time.advanceTimeBy(Duration.ofMinutes(2));

        List<Long> gaps = gaps();
        assertThat(subscribedAt).as("subscriptions in 2 minutes").hasSizeBetween(25, 600);
        assertThat(gaps).as("waits between two subscriptions")
                .allSatisfy(gap -> assertThat(gap).isBetween(MIN_BACKOFF_MS, MAX_BACKOFF_MS));
        // 200 ms doubled after every failure, +-50 %, within the bounds
        assertThat(gaps.get(0)).as("first wait").isBetween(MIN_BACKOFF_MS, 300L);
        assertThat(gaps.get(3)).as("fourth wait").isBetween(800L, 2400L);
        assertThat(gaps.get(gaps.size() - 1)).as("last wait").isBetween(MAX_BACKOFF_MS / 2, MAX_BACKOFF_MS);
        assertThat(listener.restarts()).as("restarts").isEqualTo(subscribedAt.size() - 1L);
        assertThat(listener.isRestarting()).as("restarting").isTrue();
    }

    @Test
    void shouldStartTheBackoffAgainOnceTheFlowDeliversAMessage() {
        // Fails 6 times, then delivers a message and fails again, then keeps failing
        givenSubscriptions(subscription -> subscription == 7
                ? Flux.just(receivedRecord()).concatWith(Mono.delay(Duration.ofMillis(10))
                .then(Mono.error(new KafkaException("Commit failed"))))
                : Flux.error(new KafkaException("Not authorized to access topics")));
        listener = listener(message -> Mono.empty());

        listener.startListener(topologyCreator);
        time.advanceTimeBy(Duration.ofSeconds(30));

        List<Long> gaps = gaps();
        assertThat(gaps).hasSizeGreaterThan(7);
        assertThat(gaps.get(5)).as("wait before the subscription that delivers").isGreaterThanOrEqualTo(1600L);
        // The 10 ms the subscription lived, then the first backoff again
        assertThat(gaps.get(6)).as("wait after the subscription that delivered").isBetween(MIN_BACKOFF_MS, 310L + 10L);
    }

    @Test
    void shouldReportTheListenerAsRestartingUntilItConsumesAgain() {
        givenSubscriptions(subscription -> subscription == 1
                ? Flux.error(new KafkaException("Broker may not be available"))
                : Flux.just(receivedRecord()).concatWith(Flux.never()));
        listener = listener(message -> Mono.empty());

        listener.startListener(topologyCreator);
        assertThat(listener.isRestarting()).as("restarting after the failure").isTrue();
        assertThat(listener.restartingFor()).as("time restarting, read by the health check")
                .isGreaterThan(Duration.ZERO);
        time.advanceTimeBy(Duration.ofSeconds(1));

        assertThat(subscribedAt).hasSize(2);
        assertThat(listener.isRestarting()).as("restarting once it consumes again").isFalse();
        assertThat(listener.restartingFor()).isEqualTo(Duration.ZERO);
        assertThat(listener.restarts()).isEqualTo(1);
        assertThat(listener.topics()).containsExactly("topic");
    }

    @Test
    void shouldSubscribeAgainAfterAWaitWhenTheFlowCompletes() {
        givenSubscriptions(subscription -> subscription == 1 ? Flux.empty() : Flux.never());
        listener = listener(message -> Mono.empty());

        listener.startListener(topologyCreator);
        time.advanceTimeBy(Duration.ofMillis(MIN_BACKOFF_MS - 1));
        assertThat(subscribedAt).as("subscriptions before the wait").hasSize(1);
        time.advanceTimeBy(Duration.ofSeconds(10));

        assertThat(subscribedAt).as("subscriptions").hasSize(2);
    }

    @Test
    void shouldNotSubscribeAgainOnceStopped() {
        givenSubscriptions(subscription -> Flux.error(new KafkaException("Broker may not be available")));
        listener = listener(message -> Mono.empty());

        listener.startListener(topologyCreator);
        listener.stop();
        time.advanceTimeBy(Duration.ofMinutes(1));

        assertThat(subscribedAt).hasSize(1);
    }

    private void givenSubscriptions(IntFunction<Flux<ReceiverRecord<String, byte[]>>> flowOfSubscription) {
        AtomicInteger subscriptions = new AtomicInteger();
        when(receiver.listen(anyString(), any(List.class), any())).thenAnswer(invocation -> {
            subscribedAt.add(time.now(TimeUnit.MILLISECONDS));
            return flowOfSubscription.apply(subscriptions.incrementAndGet());
        });
    }

    private List<Long> gaps() {
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < subscribedAt.size(); i++) {
            gaps.add(subscribedAt.get(i) - subscribedAt.get(i - 1));
        }
        return gaps;
    }

    private static ReceiverRecord<String, byte[]> receivedRecord() {
        ReceiverRecord<String, byte[]> receiverRecord = mock(ReceiverRecord.class);
        ReceiverOffset offset = mock(ReceiverOffset.class);
        lenient().when(receiverRecord.receiverOffset()).thenReturn(offset);
        lenient().when(receiverRecord.topic()).thenReturn("topic");
        lenient().when(receiverRecord.key()).thenReturn("key");
        lenient().when(receiverRecord.value()).thenReturn("message".getBytes(StandardCharsets.UTF_8));
        lenient().when(receiverRecord.headers()).thenReturn(new RecordHeaders());
        return receiverRecord;
    }

    private GenericMessageListenerTest.SampleListener listener(
            java.util.function.Function<org.reactivecommons.async.commons.communications.Message, Mono<Object>> handler) {
        return new GenericMessageListenerTest.SampleListener(receiver, false, false, -1, 10,
                mock(DiscardNotifier.class), "event", mock(CustomReporter.class), "appName", List.of("topic"), handler);
    }
}
