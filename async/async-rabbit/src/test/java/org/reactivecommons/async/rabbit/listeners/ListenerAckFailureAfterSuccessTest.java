package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.Receiver;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The handler of a message succeeds but its acknowledgement fails with an I/O error (e.g. the socket broke while the
 * connection is being lost): the message was processed, so the strategy for failed handlers (retry, DLQ, discard)
 * must not run, nor must the reporter receive an error of the handler.
 */
@ExtendWith(MockitoExtension.class)
class ListenerAckFailureAfterSuccessTest {

    @Mock
    private Receiver receiver;
    @Mock
    private TopologyCreator topologyCreator;
    @Mock
    private DiscardNotifier discardNotifier;
    @Mock
    private CustomReporter errorReporter;

    private final Channel channel = mock(Channel.class);
    private final AtomicInteger handled = new AtomicInteger();

    @Test
    void aSuccessfulMessageWhoseAckFailsIsNotSentToTheDlq() throws Exception {
        start(true, -1);

        verify(channel, timeout(2_000)).basicAck(1L, false);
        verify(channel, after(500).never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(errorReporter, never()).reportError(any(Throwable.class), any(Message.class), any(Object.class),
                anyBoolean());
    }

    @Test
    void aSuccessfulMessageWhoseAckFailsIsNotDiscarded() throws Exception {
        start(false, 2);

        verify(channel, timeout(2_000)).basicAck(1L, false);
        verify(discardNotifier, after(500).never()).tryNotifyDiscard(any(Message.class));
        verify(errorReporter, never()).reportError(any(Throwable.class), any(Message.class), any(Object.class),
                anyBoolean());
        org.assertj.core.api.Assertions.assertThat(handled.get()).as("handler runs").isEqualTo(1);
    }

    private void start(boolean withDLQRetry, long maxRetries) throws Exception {
        doThrow(new IOException("Broken pipe")).when(channel).basicAck(anyLong(), anyBoolean());
        lenient().when(discardNotifier.tryNotifyDiscard(any(Message.class))).thenReturn(Mono.empty());
        lenient().when(errorReporter.reportError(any(Throwable.class), any(Message.class), any(Object.class),
                anyBoolean())).thenReturn(Mono.empty());
        when(receiver.consumeManualAck(anyString(), any(ConsumeOptions.class))).thenAnswer(invocation -> {
            ConsumeOptions options = invocation.getArgument(1);
            Delivery delivery = new Delivery(new Envelope(1L, false, "exchange", "rk"),
                    new AMQP.BasicProperties.Builder().headers(Map.of()).build(), "{}".getBytes());
            return Flux.just(new AcknowledgableDelivery(delivery, channel, options.getExceptionHandler()))
                    .concatWith(Flux.never());
        });
        new SucceedingListener(new ReactiveMessageListener(receiver, topologyCreator), withDLQRetry, maxRetries,
                discardNotifier, errorReporter, handled).startListener();
    }

    private static class SucceedingListener extends GenericMessageListener {
        private final AtomicInteger handled;

        SucceedingListener(ReactiveMessageListener listener, boolean withDLQRetry, long maxRetries,
                           DiscardNotifier discardNotifier, CustomReporter errorReporter, AtomicInteger handled) {
            super("test-queue", listener, withDLQRetry, false, maxRetries, 50, discardNotifier, "command",
                    errorReporter);
            this.handled = handled;
        }

        @Override
        protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
            return message -> Mono.fromRunnable(handled::incrementAndGet);
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
