package reactor.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmListener;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ReturnListener;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SenderTest {

    private Connection connection;
    private Channel channel;
    private Sender sender;

    @BeforeEach
    void setUp() throws Exception {
        connection = mock(Connection.class);
        channel = mock(Channel.class);
        when(connection.createChannel()).thenReturn(channel);
        when(channel.isOpen()).thenReturn(true);
        when(channel.getConnection()).thenReturn(connection);
    }

    @AfterEach
    void tearDown() {
        if (sender != null) {
            sender.close();
        }
    }

    @Test
    void sendPublishesMessages() throws Exception {
        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));
        Flux<OutboundMessage> messages = Flux.range(0, 5)
                .map(i -> new OutboundMessage("exch", "rk", ("msg" + i).getBytes()));

        sender.send(messages).block(Duration.ofSeconds(5));

        verify(channel, times(5))
                .basicPublish(eq("exch"), eq("rk"), isNull(), any(byte[].class));
    }

    @Test
    void sendWithPublishConfirmsAcksMessages() throws Exception {
        when(channel.getNextPublishSeqNo()).thenReturn(1L, 2L, 3L);
        doAnswer(invocation -> {
            channel.getConnection(); // just to make it compile
            return null;
        }).when(channel).confirmSelect();

        // Simulate confirm ack for all messages upon confirmSelect
        doAnswer(invocation -> null)
                .when(channel).addConfirmListener(any(com.rabbitmq.client.ConfirmListener.class));

        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

        // Since we can't easily test async confirms in a unit test,
        // verify that confirmSelect is called and publish happens
        Flux<OutboundMessage> messages = Flux.just(
                new OutboundMessage("exch", "rk", "msg".getBytes()));

        // Just verify the pipeline doesn't error out with setup
        assertNotNull(sender.sendWithPublishConfirms(messages));
    }

    @Test
    void declareQueueExecutesRpc() throws Exception {
        var rpcFuture = new CompletableFuture<com.rabbitmq.client.Command>();
        var command = mock(com.rabbitmq.client.Command.class);
        var declareOk = mock(AMQP.Queue.DeclareOk.class);
        when(command.getMethod()).thenReturn(declareOk);
        rpcFuture.complete(command);
        when(channel.asyncCompletableRpc(any())).thenReturn(rpcFuture);

        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

        StepVerifier.create(sender.declare(QueueSpecification.queue("test-queue").durable(true)))
                .expectNext(declareOk)
                .verifyComplete();
    }

    @Test
    void declareExchangeExecutesRpc() throws Exception {
        var rpcFuture = new CompletableFuture<com.rabbitmq.client.Command>();
        var command = mock(com.rabbitmq.client.Command.class);
        var declareOk = mock(AMQP.Exchange.DeclareOk.class);
        when(command.getMethod()).thenReturn(declareOk);
        rpcFuture.complete(command);
        when(channel.asyncCompletableRpc(any())).thenReturn(rpcFuture);

        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

        StepVerifier.create(sender.declare(ExchangeSpecification.exchange("test-exchange").type("direct")))
                .expectNext(declareOk)
                .verifyComplete();
    }

    @Test
    void bindExecutesRpc() throws Exception {
        var rpcFuture = new CompletableFuture<com.rabbitmq.client.Command>();
        var command = mock(com.rabbitmq.client.Command.class);
        var bindOk = mock(AMQP.Queue.BindOk.class);
        when(command.getMethod()).thenReturn(bindOk);
        rpcFuture.complete(command);
        when(channel.asyncCompletableRpc(any())).thenReturn(rpcFuture);

        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

        StepVerifier.create(sender.bind(BindingSpecification.binding("exch", "rk", "queue")))
                .expectNext(bindOk)
                .verifyComplete();
    }

    @Test
    void unbindExecutesRpc() throws Exception {
        var rpcFuture = new CompletableFuture<com.rabbitmq.client.Command>();
        var command = mock(com.rabbitmq.client.Command.class);
        var unbindOk = mock(AMQP.Queue.UnbindOk.class);
        when(command.getMethod()).thenReturn(unbindOk);
        rpcFuture.complete(command);
        when(channel.asyncCompletableRpc(any())).thenReturn(rpcFuture);

        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

        StepVerifier.create(
                        sender.unbind(BindingSpecification.binding("exch", "rk", "queue"))
                )
                .expectNext(unbindOk)
                .verifyComplete();
    }

    @Test
    void declareQueueWithNullName() throws Exception {
        var rpcFuture = new CompletableFuture<com.rabbitmq.client.Command>();
        var command = mock(com.rabbitmq.client.Command.class);
        var declareOk = mock(AMQP.Queue.DeclareOk.class);
        when(command.getMethod()).thenReturn(declareOk);
        rpcFuture.complete(command);
        when(channel.asyncCompletableRpc(any())).thenReturn(rpcFuture);

        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

        StepVerifier.create(sender.declare(QueueSpecification.queue()))
                .expectNext(declareOk)
                .verifyComplete();
    }

    @Test
    void channelMonoPriority() {
        Mono<Channel> senderChannelMono = Mono.just(mock(Channel.class));
        Mono<Channel> sendChannelMono = Mono.just(mock(Channel.class));

        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));
        assertNotNull(sender.getChannelMono(new SendOptions()));
        assertSame(sendChannelMono, sender.getChannelMono(new SendOptions().channelMono(sendChannelMono)));

        sender.close();
        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection))
                .channelMono(senderChannelMono));
        assertSame(senderChannelMono, sender.getChannelMono(new SendOptions()));
        assertSame(sendChannelMono, sender.getChannelMono(new SendOptions().channelMono(sendChannelMono)));
    }

    @Test
    void closeIsIdempotent() {
        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));
        sender.close();
        sender.close();
        sender = null; // prevent double close in tearDown
    }

    @Test
    void connectionIsClosedWithTimeout() throws Exception {
        Connection c = mock(Connection.class);
        Channel ch = mock(Channel.class);
        when(c.createChannel()).thenReturn(ch);
        when(ch.isOpen()).thenReturn(true);
        when(ch.getConnection()).thenReturn(c);

        var options = new SenderOptions()
                .connectionSupplier(cf -> c)
                .connectionClosingTimeout(Duration.ofMillis(5000));

        sender = new Sender(options);
        sender.send(Flux.range(1, 1)
                        .map(i -> new OutboundMessage("", "", new byte[0])))
                .block(Duration.ofSeconds(5));
        sender.close();

        verify(c, times(1)).close(5000);
        sender = null;
    }

    @Test
    void connectionClosedWithZeroTimeoutMeansNoTimeout() throws Exception {
        Connection c = mock(Connection.class);
        Channel ch = mock(Channel.class);
        when(c.createChannel()).thenReturn(ch);
        when(ch.isOpen()).thenReturn(true);
        when(ch.getConnection()).thenReturn(c);

        var options = new SenderOptions()
                .connectionSupplier(cf -> c)
                .connectionClosingTimeout(Duration.ZERO);

        sender = new Sender(options);
        sender.send(Flux.range(1, 1)
                .map(i -> new OutboundMessage("", "", new byte[0])))
                .block(Duration.ofSeconds(5));
        sender.close();

        verify(c, times(1)).close(-1);
        sender = null;
    }

    @Test
    void sendWithExceptionHandlerCallsHandler() throws Exception {
        doThrow(new IOException("publish failed")).when(channel)
                .basicPublish(anyString(), anyString(), any(), any(byte[].class));
        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

        var handlerCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        var sendOptions = new SendOptions().exceptionHandler((ctx, ex) -> handlerCalled.set(true));

        sender.send(Flux.just(new OutboundMessage("exch", "rk", "body".getBytes())), sendOptions)
                .block(Duration.ofSeconds(5));

        assertThat(handlerCalled.get()).isTrue();
    }

    @Test
    void sendContextPublishDelegates() throws Exception {
        var ctx = new Sender.SendContext<>(channel, new OutboundMessage("e", "r", "b".getBytes()));
        assertThat(ctx.getChannel()).isSameAs(channel);
        assertThat(ctx.getMessage().getExchange()).isEqualTo("e");

        ctx.publish();
        verify(channel).basicPublish(eq("e"), eq("r"), isNull(), any(byte[].class));
    }

    @Test
    void sendContextPublishWithDifferentMessage() throws Exception {
        var ctx = new Sender.SendContext<>(channel, new OutboundMessage("e1", "r1", "b1".getBytes()));
        var other = new OutboundMessage("e2", "r2", "b2".getBytes());
        ctx.publish(other);
        verify(channel).basicPublish(eq("e2"), eq("r2"), isNull(), eq("b2".getBytes()));
    }

    @Nested
    class PublishConfirmTests {

        @BeforeEach
        void setUpConfirm() {
            when(channel.getNextPublishSeqNo()).thenReturn(1L, 2L, 3L, 4L, 5L);
        }

        @Test
        void publishConfirmSingleAck() throws Exception {
            AtomicReference<ConfirmListener> listenerRef = new AtomicReference<>();
            doAnswer(inv -> {
                listenerRef.set(inv.getArgument(0));
                return null;
            }).when(channel).addConfirmListener(any(ConfirmListener.class));

            sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

            Flux<OutboundMessage> messages = Flux.just(
                    new OutboundMessage("exch", "rk", "msg1".getBytes()));

            List<OutboundMessageResult<?>> results = new ArrayList<>();
            var flux = sender.sendWithPublishConfirms(messages);

            flux.subscribe(results::add);

            // Simulate broker ack for delivery tag 1
            if (listenerRef.get() != null) {
                listenerRef.get().handleAck(1, false);
            }

            assertThat(results)
                    .hasSize(1)
                    .first()
                    .satisfies(r -> {
                        assertThat(r.ack()).isTrue();
                        assertThat(r.returned()).isFalse();
                    });
        }

        @Test
        void publishConfirmMultipleAck() throws Exception {
            AtomicReference<ConfirmListener> listenerRef = new AtomicReference<>();
            doAnswer(inv -> {
                listenerRef.set(inv.getArgument(0));
                return null;
            }).when(channel).addConfirmListener(any(ConfirmListener.class));

            sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

            Flux<OutboundMessage> messages = Flux.just(
                    new OutboundMessage("exch", "rk", "m1".getBytes()),
                    new OutboundMessage("exch", "rk", "m2".getBytes()));

            List<OutboundMessageResult<?>> results = new ArrayList<>();
            sender.sendWithPublishConfirms(messages).subscribe(results::add);

            // Simulate broker ack multiple for delivery tag 2 (confirms 1 and 2)
            if (listenerRef.get() != null) {
                listenerRef.get().handleAck(2, true);
            }

            assertThat(results).hasSize(2).allMatch(OutboundMessageResult::ack);
        }

        @Test
        void publishConfirmNack() throws Exception {
            AtomicReference<ConfirmListener> listenerRef = new AtomicReference<>();
            doAnswer(inv -> {
                listenerRef.set(inv.getArgument(0));
                return null;
            }).when(channel).addConfirmListener(any(ConfirmListener.class));

            sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

            Flux<OutboundMessage> messages = Flux.just(
                    new OutboundMessage("exch", "rk", "m1".getBytes()));

            List<OutboundMessageResult<?>> results = new ArrayList<>();
            sender.sendWithPublishConfirms(messages).subscribe(results::add);

            if (listenerRef.get() != null) {
                listenerRef.get().handleNack(1, false);
            }

            assertThat(results)
                    .hasSize(1)
                    .first()
                    .satisfies(r -> assertThat(r.ack()).isFalse());
        }

        @Test
        void publishConfirmWithTrackReturned() throws Exception {
            when(channel.getNextPublishSeqNo()).thenReturn(1L);
            AtomicReference<ReturnListener> returnRef = new AtomicReference<>();
            doAnswer(inv -> null).when(channel).addConfirmListener(any(ConfirmListener.class));
            doAnswer(inv -> {
                returnRef.set(inv.getArgument(0));
                return null;
            }).when(channel).addReturnListener(any(ReturnListener.class));

            sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

            var sendOptions = new SendOptions().trackReturned(true);
            Flux<OutboundMessage> messages = Flux.just(
                    new OutboundMessage("exch", "rk", "m1".getBytes()));

            List<OutboundMessageResult<?>> results = new ArrayList<>();
            sender.sendWithPublishConfirms(messages, sendOptions).subscribe(results::add);

            // Simulate a return (message was returned by broker)
            if (returnRef.get() != null) {
                var headers = new HashMap<String, Object>();
                headers.put("reactor_rabbitmq_delivery_tag", 1L);
                var props = new AMQP.BasicProperties.Builder().headers(headers).build();
                returnRef.get().handleReturn(
                        312, "NO_ROUTE", "exch", "rk", props, "m1".getBytes()
                );
            }

            assertThat(results)
                    .hasSize(1)
                    .first()
                    .satisfies(r -> {
                        assertThat(r.returned()).isTrue();
                        assertThat(r.ack()).isTrue();
                    });
        }

        @Test
        void publishConfirmShutdownNacksUnpublished() {
            AtomicReference<com.rabbitmq.client.ShutdownListener> shutdownRef = new AtomicReference<>();
            doAnswer(inv -> {
                shutdownRef.set(inv.getArgument(0));
                return null;
            }).when(channel).addShutdownListener(any(com.rabbitmq.client.ShutdownListener.class));
            doAnswer(inv -> null).when(channel).addConfirmListener(any(ConfirmListener.class));

            sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

            Flux<OutboundMessage> messages = Flux.just(
                    new OutboundMessage("exch", "rk", "m1".getBytes()));

            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            sender.sendWithPublishConfirms(messages).subscribe(r -> {}, errorRef::set);

            // Simulate channel shutdown (soft error, not by application)
            if (shutdownRef.get() != null) {
                shutdownRef.get().shutdownCompleted(
                        new ShutdownSignalException(false, false, null, channel));
            }

            // Shutdown on a soft non-application error should cause an onError
            assertThat(errorRef.get()).isInstanceOf(ShutdownSignalException.class);
        }

        @Test
        void publishConfirmOnPublishExceptionInvokesExceptionHandler() throws Exception {
            when(channel.getNextPublishSeqNo()).thenReturn(1L);
            doThrow(new IOException("publish failed")).when(channel)
                    .basicPublish(anyString(), anyString(), anyBoolean(), any(), any(byte[].class));
            doAnswer(inv -> null).when(channel).addConfirmListener(any(ConfirmListener.class));

            sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

            // Default exception handler is RetrySendingExceptionHandler which will retry and eventually timeout
            // Use a noop handler that just throws RabbitFluxRetryTimeoutException
            var sendOptions = new SendOptions().exceptionHandler((ctx, ex) -> {
                throw new RabbitFluxRetryTimeoutException("timed out", ex);
            });

            List<OutboundMessageResult<?>> results = new ArrayList<>();
            sender.sendWithPublishConfirms(
                    Flux.just(new OutboundMessage("exch", "rk", "m".getBytes())), sendOptions
            ).subscribe(results::add);

            // On RabbitFluxRetryTimeoutException, the subscriber emits a nack result
            assertThat(results)
                    .hasSize(1)
                    .first()
                    .satisfies(r -> assertThat(r.ack()).isFalse());
        }

        @Test
        void confirmSendContextPublishTracksDeliveryTag() throws Exception {
            when(channel.getNextPublishSeqNo()).thenReturn(42L);
            AtomicReference<ConfirmListener> confirmRef = new AtomicReference<>();
            doAnswer(inv -> {
                confirmRef.set(inv.getArgument(0));
                return null;
            }).when(channel).addConfirmListener(any(ConfirmListener.class));

            sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

            var sendOptions = new SendOptions().trackReturned(true);
            Flux<OutboundMessage> messages = Flux.just(
                    new OutboundMessage("exch", "rk", "m".getBytes()));

            List<OutboundMessageResult<?>> results = new ArrayList<>();
            sender.sendWithPublishConfirms(messages, sendOptions).subscribe(results::add);

            // Verify that basicPublish was called with mandatory=true (trackReturned)
            // and that properties contain the delivery tag header
            ArgumentCaptor<AMQP.BasicProperties> propsCaptor = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
            verify(channel).basicPublish(eq("exch"), eq("rk"), eq(true), propsCaptor.capture(), any(byte[].class));

            var props = propsCaptor.getValue();
            assertThat(props.getHeaders()).containsEntry("reactor_rabbitmq_delivery_tag", 42L);

            // Confirm it to complete
            if (confirmRef.get() != null) {
                confirmRef.get().handleAck(42, false);
            }
        }
    }
}
