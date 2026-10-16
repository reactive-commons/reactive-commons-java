package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmListener;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.SendConfirmUnknownException;
import org.reactivecommons.async.commons.exceptions.SendFailureNoAckException;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests how {@link ReactiveMessageSender} behaves when one of the long-lived channels it publishes through fails.
 * They use the REAL reactor-rabbitmq {@link Sender} on top of mocked channels that behave like a broker (every
 * publish is confirmed right away unless {@link #confirmsEnabled} is off), so the only thing that differs between
 * the scenarios is the failure that is injected.
 *
 * <p>Expected behavior: every send finishes (success or error) within a bounded time with its own outcome, and a
 * flow whose channel failed is replaced by a new one.
 */
class ReactiveMessageSenderChannelFailureTest {

    /**
     * Number of publisher-confirm subscriptions created by ReactiveMessageSender (NUMBER_OF_SENDER_SUBSCRIPTIONS).
     */
    private static final int CONFIRM_SUBSCRIPTIONS = 4;
    private static final Duration SHORT_SEND_TIMEOUT = Duration.ofMillis(500);

    private final MessageConverter converter = new RabbitJacksonMessageConverter(new JsonMapper());

    private final List<Channel> channels = new CopyOnWriteArrayList<>();
    private final Map<Channel, ConfirmListener> confirmListeners = new ConcurrentHashMap<>();
    private final Map<Channel, ShutdownListener> shutdownListeners = new ConcurrentHashMap<>();
    private final AtomicBoolean confirmsEnabled = new AtomicBoolean(true);

    /**
     * Each long-lived publishing flow subscribes to this Mono and gets its own channel (like a pool would). Order of
     * creation: the 4 publisher-confirm flows first, then the "no confirm" flow, then the replacements.
     */
    private final Mono<Channel> channelMono = Mono.fromSupplier(() -> {
        Channel channel = brokerChannel();
        channels.add(channel);
        return channel;
    });

    private ReactiveMessageSender newMessageSender(Mono<Channel> channels, Duration sendTimeout) {
        final Sender sender = new Sender(new SenderOptions().channelMono(channels));
        return new ReactiveMessageSender(sender, "TestApp", converter, null, false,
                mock(UnroutableMessageNotifier.class), sendTimeout);
    }

    @Test
    void sendWithConfirmKeepsConfirmingEveryMessageAfterTheBrokerClosesOneOfTheChannels() throws Exception {
        ReactiveMessageSender messageSender = newMessageSender(channelMono, ReactiveMessageSender.DEFAULT_SEND_TIMEOUT);
        assertThat(channels).hasSize(CONFIRM_SUBSCRIPTIONS + 1);

        // 1. Sanity check: with all channels healthy every message is confirmed.
        Counters healthy = sendMessages(messageSender, 100);
        awaitSettled(healthy, 100);
        assertThat(healthy.completed.get()).as("healthy broker: confirmed messages").isEqualTo(100);

        // 2. A "soft" channel error (e.g. publishing to a missing exchange => 404 NOT_FOUND closes the channel,
        //    but the connection stays up).
        shutdownListeners.get(channels.get(0)).shutdownCompleted(channelError(channels.get(0)));

        // 3. The broken flow is replaced by a new one with a new channel, and every message is confirmed.
        int total = 200;
        Counters afterFailure = sendMessages(messageSender, total);
        awaitSettled(afterFailure, total);
        System.out.printf("After channel error: sent=%d completed=%d failed=%d hanging=%d%n",
                total, afterFailure.completed.get(), afterFailure.failed.get(), afterFailure.hanging(total));

        assertThat(afterFailure.completed.get()).as("confirmed messages").isEqualTo(total);
        assertThat(afterFailure.failed.get()).as("failed messages").isZero();
        assertThat(afterFailure.hanging(total)).as("messages never resolved").isZero();

        // 4. After the backoff the flow is re-subscribed with a new channel, which is used like the others.
        awaitChannels(CONFIRM_SUBSCRIPTIONS + 2);
        assertThat(channels).as("the closed channel was replaced by a new one").hasSize(CONFIRM_SUBSCRIPTIONS + 2);
        Counters afterResubscription = sendMessages(messageSender, 40);
        awaitSettled(afterResubscription, 40);
        assertThat(afterResubscription.completed.get()).isEqualTo(40);
        verify(channels.get(CONFIRM_SUBSCRIPTIONS + 1), atLeastOnce()).basicPublish(anyString(),
                anyString(), anyBoolean(), any(AMQP.BasicProperties.class), any(byte[].class));
    }

    @Test
    void messagesWaitingForTheirConfirmWhenTheirChannelIsClosedFailWithUnknownOutcome() throws Exception {
        ReactiveMessageSender messageSender = newMessageSender(channelMono, ReactiveMessageSender.DEFAULT_SEND_TIMEOUT);
        confirmsEnabled.set(false); // the broker received the messages, but the confirms did not arrive yet

        int inFlight = 8;
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Counters inDoubt = sendMessages(messageSender, inFlight, errors);
        awaitPublished(inFlight);
        for (int i = 0; i < CONFIRM_SUBSCRIPTIONS; i++) {
            shutdownListeners.get(channels.get(i)).shutdownCompleted(channelError(channels.get(i)));
        }
        awaitSettled(inDoubt, inFlight);

        assertThat(inDoubt.failed.get()).as("in-doubt messages are reported, not left hanging").isEqualTo(inFlight);
        assertThat(errors).allSatisfy(e -> assertThat(e).isInstanceOf(SendConfirmUnknownException.class));

        // The replacement flows (new channels) work normally.
        confirmsEnabled.set(true);
        Counters afterFailure = sendMessages(messageSender, 20);
        awaitSettled(afterFailure, 20);
        assertThat(afterFailure.completed.get()).isEqualTo(20);
    }

    @Test
    void sendNoConfirmReportsThePublishFailureAndKeepsPublishingThroughANewChannel() throws Exception {
        ReactiveMessageSender messageSender = newMessageSender(channelMono, ReactiveMessageSender.DEFAULT_SEND_TIMEOUT);
        Channel noConfirmChannel = channels.get(CONFIRM_SUBSCRIPTIONS);
        doThrow(new IOException("connection reset")).when(noConfirmChannel)
                .basicPublish(anyString(), anyString(), any(AMQP.BasicProperties.class), any(byte[].class));

        // The failed publish is reported to its caller, without blocking it.
        long start = System.nanoTime();
        StepVerifier.create(messageSender.sendNoConfirm("first", "exchange", "rkey", new HashMap<>(), true))
                .expectError(SendFailureNoAckException.class)
                .verify(Duration.ofSeconds(5));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));

        // The flow is re-subscribed with a new channel: later messages are published.
        for (int i = 0; i < 10; i++) {
            StepVerifier.create(messageSender.sendNoConfirm("msg-" + i, "exchange", "rkey", new HashMap<>(), true))
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
        }
        assertThat(channels).hasSize(CONFIRM_SUBSCRIPTIONS + 2);
        verify(noConfirmChannel, times(1))
                .basicPublish(anyString(), anyString(), any(AMQP.BasicProperties.class), any(byte[].class));
        verify(channels.get(CONFIRM_SUBSCRIPTIONS + 1), times(10))
                .basicPublish(anyString(), anyString(), any(AMQP.BasicProperties.class), any(byte[].class));
    }

    @Test
    void sendNoConfirmPublishesOnlyWhenSubscribed() throws Exception {
        ReactiveMessageSender messageSender = newMessageSender(channelMono, ReactiveMessageSender.DEFAULT_SEND_TIMEOUT);
        Channel noConfirmChannel = channels.get(CONFIRM_SUBSCRIPTIONS);

        Mono<Void> send = messageSender.sendNoConfirm("lazy", "exchange", "rkey", new HashMap<>(), true);
        verify(noConfirmChannel, after(300).never())
                .basicPublish(anyString(), anyString(), any(AMQP.BasicProperties.class), any(byte[].class));

        StepVerifier.create(send).expectComplete().verify(Duration.ofSeconds(5));
        verify(noConfirmChannel, times(1))
                .basicPublish(anyString(), anyString(), any(AMQP.BasicProperties.class), any(byte[].class));
    }

    @Test
    void sendsIssuedBeforeTheFlowsAreReadyWaitForThem() throws Exception {
        // The channels are obtained asynchronously (e.g. the connection is still being established).
        ReactiveMessageSender messageSender = newMessageSender(
                channelMono.delaySubscription(Duration.ofMillis(300)), ReactiveMessageSender.DEFAULT_SEND_TIMEOUT);
        assertThat(channels).as("no flow is ready yet").isEmpty();

        Counters early = sendMessages(messageSender, 20);
        StepVerifier.create(messageSender.sendNoConfirm("early", "exchange", "rkey", new HashMap<>(), true))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        awaitSettled(early, 20);

        assertThat(early.completed.get()).as("early messages are not lost").isEqualTo(20);
    }

    @Test
    void sendsFailWithinTheSendTimeoutWhenNoFlowEverBecomesReady() {
        ReactiveMessageSender messageSender = newMessageSender(Mono.never(), SHORT_SEND_TIMEOUT);

        StepVerifier.create(messageSender.sendWithConfirm("never", "exchange", "rkey", new HashMap<>(), true))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isExactlyInstanceOf(SendFailureNoAckException.class)
                        .hasMessageContaining("no RabbitMQ publishing flow available"))
                .verify(Duration.ofSeconds(5));
        StepVerifier.create(messageSender.sendNoConfirm("never", "exchange", "rkey", new HashMap<>(), true))
                .expectError(SendFailureNoAckException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void sendWithConfirmFailsWithUnknownOutcomeWhenTheConfirmDoesNotArriveInTime() {
        ReactiveMessageSender messageSender = newMessageSender(channelMono, SHORT_SEND_TIMEOUT);
        confirmsEnabled.set(false);

        StepVerifier.create(messageSender.sendWithConfirm("silent", "exchange", "rkey", new HashMap<>(), true))
                .expectError(SendConfirmUnknownException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void sendWithConfirmBatchFailsInsteadOfHangingWhenItsChannelIsClosed() {
        ReactiveMessageSender messageSender = newMessageSender(channelMono, ReactiveMessageSender.DEFAULT_SEND_TIMEOUT);
        confirmsEnabled.set(false);

        StepVerifier.create(messageSender.sendWithConfirmBatch(Flux.just("a", "b", "c"), "exchange", "rkey",
                        new HashMap<>(), true))
                .then(() -> {
                    awaitChannels(CONFIRM_SUBSCRIPTIONS + 2);
                    Channel batchChannel = channels.get(CONFIRM_SUBSCRIPTIONS + 1);
                    shutdownListeners.get(batchChannel).shutdownCompleted(channelError(batchChannel));
                })
                .expectError(SendConfirmUnknownException.class)
                .verify(Duration.ofSeconds(5));
    }

    // ---------------------------------------------------------------------------------------------------------

    private record Counters(AtomicInteger completed, AtomicInteger failed) {
        int hanging(int total) {
            return total - completed.get() - failed.get();
        }
    }

    private Counters sendMessages(ReactiveMessageSender messageSender, int total) {
        return sendMessages(messageSender, total, new CopyOnWriteArrayList<>());
    }

    private Counters sendMessages(ReactiveMessageSender messageSender, int total, List<Throwable> errors) {
        Counters counters = new Counters(new AtomicInteger(), new AtomicInteger());
        for (int i = 0; i < total; i++) {
            messageSender.sendWithConfirm("payload-" + i, "exchange", "rkey", new HashMap<>(), true)
                    .subscribe(v -> {
                    }, e -> {
                        errors.add(e);
                        counters.failed.incrementAndGet();
                    }, counters.completed::incrementAndGet);
        }
        return counters;
    }

    private void awaitSettled(Counters counters, int total) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && counters.hanging(total) > 0) {
            Thread.sleep(20);
        }
    }

    private void awaitChannels(int count) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && channels.size() < count) {
            sleep(20);
        }
    }

    private void awaitPublished(int count) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && publishedWithConfirms() < count) {
            Thread.sleep(20);
        }
        assertThat(publishedWithConfirms()).isEqualTo(count);
    }

    private long publishedWithConfirms() {
        return channels.stream().mapToLong(c -> ((ConfirmingChannel) confirmListeners.getOrDefault(c, NO_LISTENER))
                .published()).sum();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ShutdownSignalException channelError(Channel channel) {
        // soft error (channel-level), not initiated by the application
        return new ShutdownSignalException(false, false, null, channel);
    }

    private static final ConfirmingChannel NO_LISTENER = new ConfirmingChannel(null);

    /**
     * Wraps the confirm listener registered by the sender and counts the publishes of its channel.
     */
    private record ConfirmingChannel(ConfirmListener delegate, AtomicLong count) implements ConfirmListener {
        ConfirmingChannel(ConfirmListener delegate) {
            this(delegate, new AtomicLong());
        }

        long published() {
            return count.get();
        }

        @Override
        public void handleAck(long deliveryTag, boolean multiple) throws IOException {
            delegate.handleAck(deliveryTag, multiple);
        }

        @Override
        public void handleNack(long deliveryTag, boolean multiple) throws IOException {
            delegate.handleNack(deliveryTag, multiple);
        }
    }

    /**
     * A channel that acts like a broker: it remembers the listeners registered by the sender and, while
     * {@link #confirmsEnabled} is on, confirms (acks) every message published with publisher confirms right away.
     */
    private Channel brokerChannel() {
        try {
            Channel channel = mock(Channel.class);
            AtomicLong nextSeqNo = new AtomicLong(1);

            when(channel.isOpen()).thenReturn(true);
            when(channel.getNextPublishSeqNo()).thenAnswer(invocation -> nextSeqNo.get());
            doAnswer(invocation -> {
                confirmListeners.put(channel, new ConfirmingChannel(invocation.getArgument(0)));
                return null;
            }).when(channel).addConfirmListener(any(ConfirmListener.class));
            doAnswer(invocation -> {
                shutdownListeners.put(channel, invocation.getArgument(0));
                return null;
            }).when(channel).addShutdownListener(any(ShutdownListener.class));
            doAnswer(invocation -> {
                ConfirmingChannel listener = (ConfirmingChannel) confirmListeners.get(channel);
                listener.count().incrementAndGet();
                long seqNo = nextSeqNo.getAndIncrement();
                if (confirmsEnabled.get()) {
                    listener.handleAck(seqNo, false);
                }
                return null;
            }).when(channel).basicPublish(anyString(), anyString(), anyBoolean(), any(AMQP.BasicProperties.class),
                    any(byte[].class));
            return channel;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
