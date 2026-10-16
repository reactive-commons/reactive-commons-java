package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmListener;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests that document how {@link ReactiveMessageSender} behaves when one of the long-lived
 * channels it uses to publish fails. They use the REAL reactor-rabbitmq {@link Sender} on top of mocked channels
 * that behave like a healthy broker (every publish is confirmed immediately), so the only thing that differs
 * between the senders is the channel failure that is injected.
 *
 * <p>The assertions describe the CURRENT behavior (a defect). Once the sender is made resilient, they should be
 * inverted: every {@code sendWithConfirm} must finish (success or error) and {@code sendNoConfirm} must keep
 * publishing after a failure.
 */
class ReactiveMessageSenderChannelFailureTest {

    /**
     * Number of publisher-confirm subscriptions created by ReactiveMessageSender (NUMBER_OF_SENDER_SUBSCRIPTIONS).
     */
    private static final int CONFIRM_SUBSCRIPTIONS = 4;

    private final MessageConverter converter = new RabbitJacksonMessageConverter(new JsonMapper());

    private final List<Channel> channels = new CopyOnWriteArrayList<>();
    private final Map<Channel, ConfirmListener> confirmListeners = new ConcurrentHashMap<>();
    private final Map<Channel, ShutdownListener> shutdownListeners = new ConcurrentHashMap<>();

    private ReactiveMessageSender messageSender;

    @BeforeEach
    void init() {
        // Each long-lived publishing flow subscribes once to this Mono, and gets its own channel (like a pool would).
        // Order of creation: 4 publisher-confirm flows first, then the "no confirm" flow.
        final Mono<Channel> channelMono = Mono.fromSupplier(() -> {
            Channel channel = healthyBrokerChannel();
            channels.add(channel);
            return channel;
        });
        final Sender sender = new Sender(new SenderOptions().channelMono(channelMono));
        messageSender = new ReactiveMessageSender(sender, "TestApp", converter, null, false,
                mock(UnroutableMessageNotifier.class));
    }

    @Test
    void sendWithConfirmNeverFinishesWhenOneSenderSubscriptionDiesAfterAChannelError() throws Exception {
        assertThat(channels).hasSize(CONFIRM_SUBSCRIPTIONS + 1);

        // 1. Sanity check: with all channels healthy every message is confirmed.
        Counters healthy = sendMessages(100);
        awaitSettled(healthy, 100);
        assertThat(healthy.completed.get()).as("healthy broker: confirmed messages").isEqualTo(100);

        // 2. A "soft" channel error (e.g. publishing to a missing exchange => 404 NOT_FOUND closes the channel,
        //    but the connection stays up). It is not a hard error and not initiated by the application.
        ShutdownSignalException channelError = mock(ShutdownSignalException.class);
        when(channelError.isHardError()).thenReturn(false);
        when(channelError.isInitiatedByApplication()).thenReturn(false);
        try {
            shutdownListeners.get(channels.get(0)).shutdownCompleted(channelError);
        } catch (Throwable expected) {
            // The internal `.subscribe()` has no error handler: Reactor throws ErrorCallbackNotImplemented here and
            // nothing re-subscribes. In production this surfaces only as a log line from the client's thread.
            System.out.println("Sender flow #0 terminated with: " + expected);
        }

        // 3. The broker and the other 3 channels are perfectly fine, yet some sends are never resolved.
        int total = 200;
        Counters afterFailure = sendMessages(total);
        awaitSettled(afterFailure, total);

        int completed = afterFailure.completed.get();
        int failed = afterFailure.failed.get();
        int hanging = total - completed - failed;
        System.out.printf("After channel error: sent=%d completed=%d failed=%d HANGING(never resolved)=%d%n",
                total, completed, failed, hanging);

        assertThat(completed).as("messages routed to healthy subscriptions still work").isGreaterThan(0);
        assertThat(failed).as("no error is ever reported to the caller").isZero();
        assertThat(hanging)
                .as("messages routed to the dead subscription: the Mono never emits success nor error (~1/4)")
                .isGreaterThan(0);
    }

    @Test
    void sendNoConfirmSilentlyDropsEverythingAfterOnePublishFailure() throws Exception {
        Channel noConfirmChannel = channels.get(CONFIRM_SUBSCRIPTIONS);
        doThrow(new IOException("connection reset")).when(noConfirmChannel)
                .basicPublish(anyString(), anyString(), any(AMQP.BasicProperties.class), any(byte[].class));

        // First publish fails with a non retryable exception: the flow terminates with error.
        try {
            messageSender.sendNoConfirm("first", "exchange", "rkey", new HashMap<>(), true).block();
        } catch (Throwable expected) {
            System.out.println("First sendNoConfirm failed with: " + expected);
        }

        // The channel is healthy again, but the flow is dead: the call looks successful and nothing is published.
        doAnswer(invocation -> null).when(noConfirmChannel)
                .basicPublish(anyString(), anyString(), any(AMQP.BasicProperties.class), any(byte[].class));
        for (int i = 0; i < 10; i++) {
            StepVerifier.create(messageSender.sendNoConfirm("msg-" + i, "exchange", "rkey", new HashMap<>(), true))
                    .verifyComplete();
        }

        // Only the first (failed) attempt ever reached the channel; the 10 later messages vanished silently.
        verify(noConfirmChannel, times(1))
                .basicPublish(anyString(), anyString(), any(AMQP.BasicProperties.class), any(byte[].class));
    }

    // ---------------------------------------------------------------------------------------------------------

    private record Counters(AtomicInteger completed, AtomicInteger failed) {
    }

    private Counters sendMessages(int total) throws InterruptedException {
        Counters counters = new Counters(new AtomicInteger(), new AtomicInteger());
        for (int i = 0; i < total; i++) {
            messageSender.sendWithConfirm("payload-" + i, "exchange", "rkey", new HashMap<>(), true)
                    .subscribe(v -> {
                    }, e -> counters.failed.incrementAndGet(), counters.completed::incrementAndGet);
            // ReactiveMessageSender picks the subscription with System.currentTimeMillis() % 4,
            // so spread the sends over time to reach every subscription.
            Thread.sleep(1);
        }
        return counters;
    }

    private void awaitSettled(Counters counters, int total) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline
                && counters.completed.get() + counters.failed.get() < total) {
            Thread.sleep(50);
        }
    }

    /**
     * A channel that acts like a healthy broker: it remembers the listeners registered by the sender and
     * confirms (acks) every message published with publisher confirms right away.
     */
    private Channel healthyBrokerChannel() {
        try {
            Channel channel = mock(Channel.class);
            AtomicLong nextSeqNo = new AtomicLong(1);

            when(channel.getNextPublishSeqNo()).thenAnswer(invocation -> nextSeqNo.get());
            doAnswer(invocation -> {
                ConfirmListener listener = invocation.getArgument(0);
                confirmListeners.put(channel, listener);
                return listener;
            }).when(channel).addConfirmListener(any(ConfirmListener.class));
            doAnswer(invocation -> {
                shutdownListeners.put(channel, invocation.getArgument(0));
                return null;
            }).when(channel).addShutdownListener(any(ShutdownListener.class));
            doAnswer(invocation -> {
                confirmListeners.get(channel).handleAck(nextSeqNo.getAndIncrement(), false);
                return null;
            }).when(channel).basicPublish(anyString(), anyString(), anyBoolean(), any(AMQP.BasicProperties.class),
                    any(byte[].class));
            return channel;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
