package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.RetryPolicy;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.RabbitFluxException;
import reactor.rabbitmq.Receiver;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Acknowledging a message whose channel is closed (e.g. while the client recovers the connection) must not block
 * the thread: acks run on the thread that completed the handler, which can be a Netty event loop shared with the
 * HTTP server. The broker requeues the unacknowledged message when the channel closes, so there is nothing to retry.
 */
@ExtendWith(MockitoExtension.class)
class ListenerAcknowledgmentFailureTest {

    private static final Duration NON_BLOCKING = Duration.ofSeconds(1);

    @Mock
    private Receiver receiver;
    @Mock
    private TopologyCreator topologyCreator;

    private final Channel channel = mock(Channel.class);

    @Test
    void messageListenerAckOnAClosedChannelDoesNotBlockTheThread() throws Exception {
        AcknowledgableDelivery delivery = delivery(messageListenerConsumeOptions());
        doThrow(closedByConnectionLoss()).when(channel).basicAck(anyLong(), anyBoolean());

        assertThat(timeToRun(delivery::ack)).isLessThan(NON_BLOCKING);
        verify(channel, times(1)).basicAck(1L, false);
    }

    @Test
    void messageListenerNackOnAClosedChannelDoesNotBlockTheThread() throws Exception {
        AcknowledgableDelivery delivery = delivery(messageListenerConsumeOptions());
        doThrow(closedByConnectionLoss()).when(channel).basicNack(anyLong(), anyBoolean(), anyBoolean());

        assertThat(timeToRun(() -> delivery.nack(true))).isLessThan(NON_BLOCKING);
        verify(channel, times(1)).basicNack(1L, false, true);
    }

    @Test
    void messageListenerAckFailureOnAnOpenChannelIsReported() throws Exception {
        AcknowledgableDelivery delivery = delivery(messageListenerConsumeOptions());
        doThrow(new IOException("unexpected")).when(channel).basicAck(anyLong(), anyBoolean());

        assertThatThrownBy(delivery::ack).isInstanceOf(RabbitFluxException.class);
    }

    @Test
    void replyListenerAckOnAClosedChannelDoesNotBlockTheThread() throws Exception {
        AcknowledgableDelivery delivery = delivery(replyListenerConsumeOptions());
        doThrow(closedByConnectionLoss()).when(channel).basicAck(anyLong(), anyBoolean());

        assertThat(timeToRun(delivery::ack)).isLessThan(NON_BLOCKING);
        verify(channel, times(1)).basicAck(1L, false);
    }

    /**
     * The options the listener really uses to consume (they hold the acknowledgment exception handler).
     */
    private ConsumeOptions messageListenerConsumeOptions() {
        when(receiver.consumeManualAck(anyString(), any(ConsumeOptions.class))).thenReturn(Flux.never());
        new TestListener(new ReactiveMessageListener(receiver, topologyCreator)).startListener();
        ArgumentCaptor<ConsumeOptions> options = ArgumentCaptor.forClass(ConsumeOptions.class);
        verify(receiver).consumeManualAck(anyString(), options.capture());
        return options.getValue();
    }

    private ConsumeOptions replyListenerConsumeOptions() {
        when(topologyCreator.declare(any(reactor.rabbitmq.QueueSpecification.class))).thenReturn(Mono.empty());
        when(topologyCreator.bind(any())).thenReturn(Mono.empty());
        when(receiver.consumeAutoAck(anyString(), any(ConsumeOptions.class))).thenReturn(Flux.never());
        new ApplicationReplyListener(new ReactiveReplyRouter(), new ReactiveMessageListener(receiver, topologyCreator),
                "replies-queue", "globalReply", false).startListening("route");
        ArgumentCaptor<ConsumeOptions> options = ArgumentCaptor.forClass(ConsumeOptions.class);
        verify(receiver, org.mockito.Mockito.timeout(2_000)).consumeAutoAck(anyString(), options.capture());
        return options.getValue();
    }

    private AcknowledgableDelivery delivery(ConsumeOptions options) {
        Delivery delivery = new Delivery(new Envelope(1L, false, "exchange", "rk"),
                new AMQP.BasicProperties.Builder().headers(Map.of()).build(), "{}".getBytes());
        return new AcknowledgableDelivery(delivery, channel, options.getExceptionHandler());
    }

    /**
     * What the client throws when acknowledging on a channel of a connection that is being recovered.
     */
    private static AlreadyClosedException closedByConnectionLoss() {
        return new AlreadyClosedException(new ShutdownSignalException(true, false, null, mock(Connection.class)));
    }

    private static Duration timeToRun(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return Duration.ofNanos(System.nanoTime() - start);
    }

    private static class TestListener extends GenericMessageListener {
        TestListener(ReactiveMessageListener listener) {
            super(new ListenerContext<>(listener, null, null, new RetryPolicy(false, -1, 1000), false,
                    mock(DiscardNotifier.class), mock(CustomReporter.class)), "test-queue", "command");
        }

        @Override
        protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
            return message -> Mono.empty();
        }

        @Override
        protected String getExecutorPath(AcknowledgableDelivery msj) {
            return "path";
        }

        @Override
        protected Object parseMessageForReporter(Message msj) {
            return msj;
        }

        @Override
        protected String getKind() {
            return "test";
        }
    }
}
