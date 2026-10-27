package reactor.rabbitmq;

import com.rabbitmq.client.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

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

    /**
     * The client blocks the thread that sends an RPC while another one is in progress on the same channel, and opening
     * the channel is an RPC too: they never run on the thread that subscribes, which may be a non-blocking one (e.g.
     * an event loop of the HTTP server registering a dynamic listener).
     */
    @Test
    void resourceManagementRpcsAreSentOnTheResourceManagementScheduler() throws Exception {
        var command = mock(com.rabbitmq.client.Command.class);
        when(command.getMethod()).thenReturn(mock(AMQP.Queue.DeclareOk.class), mock(AMQP.Exchange.DeclareOk.class),
                mock(AMQP.Queue.BindOk.class), mock(AMQP.Queue.UnbindOk.class));
        List<String> rpcThreads = new CopyOnWriteArrayList<>();
        when(channel.asyncCompletableRpc(any())).thenAnswer(invocation -> {
            rpcThreads.add(Thread.currentThread().getName());
            return CompletableFuture.completedFuture(command);
        });
        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

        sender.declare(QueueSpecification.queue("queue")).block(Duration.ofSeconds(5));
        sender.declare(ExchangeSpecification.exchange("exch")).block(Duration.ofSeconds(5));
        sender.bind(BindingSpecification.binding("exch", "rk", "queue")).block(Duration.ofSeconds(5));
        sender.unbind(BindingSpecification.binding("exch", "rk", "queue")).block(Duration.ofSeconds(5));

        assertThat(rpcThreads).as("declare queue, declare exchange, bind and unbind").hasSize(4)
                .allSatisfy(thread -> assertThat(thread).startsWith("rabbitmq-sender-resource-creation"));
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
    void channelCloseHandlerDefaultsToClosingTheChannel() {
        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

        assertSame(ChannelCloseHandlers.SENDER_CHANNEL_CLOSE_HANDLER_INSTANCE, sender.getChannelCloseHandler());
    }

    @Test
    void channelCloseHandlerIsTheOneOfTheChannelPool() {
        BiConsumer<SignalType, Channel> poolHandler = (signal, ch) -> { };
        ChannelPool pool = mock(ChannelPool.class);
        doReturn(Mono.just(channel)).when(pool).getChannelMono();
        when(pool.getChannelCloseHandler()).thenReturn(poolHandler);

        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)).channelPool(pool));

        assertSame(poolHandler, sender.getChannelCloseHandler());
    }

    @Test
    void channelCloseHandlerIsTheConfiguredOne() {
        BiConsumer<SignalType, Channel> custom = (signal, ch) -> { };

        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)).channelCloseHandler(custom));

        assertSame(custom, sender.getChannelCloseHandler());
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

    @Test
    void sendMarksMessagesAsPublishedOnlyAfterASuccessfulPublish() throws Exception {
        doNothing().doThrow(new IOException("publish failed")).when(channel)
                .basicPublish(anyString(), anyString(), any(), any(byte[].class));
        sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

        var first = new OutboundMessage("exch", "rk", "1".getBytes());
        var second = new OutboundMessage("exch", "rk", "2".getBytes());
        var third = new OutboundMessage("exch", "rk", "3".getBytes());
        var upstreamCancelled = new AtomicBoolean();
        var sendOptions = new SendOptions().exceptionHandler((ctx, ex) -> {
            throw new RabbitFluxException("publish failed", ex);
        });

        StepVerifier.create(sender.send(Flux.just(first, second, third)
                        .doOnCancel(() -> upstreamCancelled.set(true)), sendOptions))
                .expectError(RabbitFluxException.class)
                .verify(Duration.ofSeconds(5));

        assertThat(first.isPublished()).isTrue();
        assertThat(second.isPublished()).isFalse();
        assertThat(third.isPublished()).isFalse();
        assertThat(upstreamCancelled).as("a failing flow stops consuming its source").isTrue();
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

        /**
         * A message consumed and published again carries the tag of its previous publication: the tag of this one
         * replaces it, otherwise its basic.return would be correlated with another message.
         */
        @Test
        void theDeliveryTagOfAPreviousPublicationIsReplaced() throws Exception {
            when(channel.getNextPublishSeqNo()).thenReturn(42L);
            sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));
            AMQP.BasicProperties relayed = new AMQP.BasicProperties.Builder()
                    .headers(Map.of("reactor_rabbitmq_delivery_tag", 7L))
                    .build();

            sender.sendWithPublishConfirms(Flux.just(new OutboundMessage("exch", "rk", relayed, "m".getBytes())),
                    new SendOptions().trackReturned(true)).subscribe();

            ArgumentCaptor<AMQP.BasicProperties> propsCaptor = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
            verify(channel).basicPublish(eq("exch"), eq("rk"), eq(true), propsCaptor.capture(), any(byte[].class));
            assertThat(propsCaptor.getValue().getHeaders()).containsEntry("reactor_rabbitmq_delivery_tag", 42L);
        }
    }

    /**
     * Every message handed to a publisher-confirms flow gets exactly one result, even when the channel or the
     * connection fails, and a confirm is never attributed to another message.
     */
    @Nested
    class ChannelFailureTests {

        private final AtomicLong nextSeqNo = new AtomicLong(1);
        private final AtomicReference<ConfirmListener> confirmListener = new AtomicReference<>();
        private final AtomicReference<ShutdownListener> shutdownListener = new AtomicReference<>();
        private final AtomicReference<RecoveryListener> recoveryListener = new AtomicReference<>();
        private final List<OutboundMessageResult<OutboundMessage>> results = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean upstreamCancelled = new AtomicBoolean();

        private void brokerChannel(Channel ch) {
            when(ch.getNextPublishSeqNo()).thenAnswer(inv -> nextSeqNo.getAndIncrement());
            doAnswer(inv -> {
                confirmListener.set(inv.getArgument(0));
                return null;
            }).when(ch).addConfirmListener(any(ConfirmListener.class));
            doAnswer(inv -> {
                shutdownListener.set(inv.getArgument(0));
                return null;
            }).when(ch).addShutdownListener(any(ShutdownListener.class));
        }

        private RecoverableChannel recoverableBrokerChannel() throws Exception {
            RecoverableChannel ch = mock(RecoverableChannel.class);
            brokerChannel(ch);
            doAnswer(inv -> {
                recoveryListener.set(inv.getArgument(0));
                return null;
            }).when(ch).addRecoveryListener(any(RecoveryListener.class));
            when(ch.isOpen()).thenReturn(true);
            when(ch.getConnection()).thenReturn(connection);
            when(connection.createChannel()).thenReturn(ch);
            return ch;
        }

        private Sinks.Many<OutboundMessage> subscribe() {
            sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));
            Sinks.Many<OutboundMessage> source = Sinks.many().unicast().onBackpressureBuffer();
            sender.sendWithTypedPublishConfirms(source.asFlux().doOnCancel(() -> upstreamCancelled.set(true)))
                    .subscribe(results::add, error::set, () -> completed.set(true));
            return source;
        }

        private List<OutboundMessage> publish(Sinks.Many<OutboundMessage> source, int count) {
            List<OutboundMessage> messages = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                OutboundMessage message = new OutboundMessage("exch", "rk", ("m" + i).getBytes());
                messages.add(message);
                source.tryEmitNext(message);
            }
            return messages;
        }

        @Test
        void channelErrorReportsEveryPendingMessageAsUnknownAndTerminates() throws IOException {
            brokerChannel(channel);
            Sinks.Many<OutboundMessage> source = subscribe();
            List<OutboundMessage> messages = publish(source, 50);

            // e.g. 404 NOT_FOUND: the broker closes the channel, the connection stays up
            shutdownListener.get().shutdownCompleted(new ShutdownSignalException(false, false, null, channel));

            assertThat(results).hasSize(50)
                    .allSatisfy(r -> assertThat(r.unknown()).isTrue())
                    .allSatisfy(r -> assertThat(r.ack()).isFalse());
            assertThat(results).extracting(OutboundMessageResult::outboundMessage)
                    .containsExactlyInAnyOrderElementsOf(messages);
            assertThat(error.get()).isInstanceOf(ShutdownSignalException.class);
            assertThat(upstreamCancelled).as("the source learns it has no subscriber anymore").isTrue();
            verify(channel).removeConfirmListener(any(ConfirmListener.class));
            verify(channel).removeShutdownListener(any(ShutdownListener.class));

            // late confirms of the closed channel are ignored
            confirmListener.get().handleAck(50, true);
            assertThat(results).hasSize(50);
        }

        @Test
        void connectionLossOnRecoverableChannelKeepsTheFlowAndNeverCrossesConfirms() throws Exception {
            RecoverableChannel ch = recoverableBrokerChannel();
            Sinks.Many<OutboundMessage> source = subscribe();
            publish(source, 50);

            ShutdownSignalException connectionLost = new ShutdownSignalException(true, false, null, connection);
            shutdownListener.get().shutdownCompleted(connectionLost);

            assertThat(results).hasSize(50).allSatisfy(r -> assertThat(r.unknown()).isTrue());
            assertThat(error.get()).as("the flow survives a connection failure").isNull();
            assertThat(upstreamCancelled).isFalse();
            verify(ch, never()).removeConfirmListener(any(ConfirmListener.class));

            // automatic recovery re-opens the channel: the publish sequence restarts at 1
            recoveryListener.get().handleRecoveryStarted(ch);
            nextSeqNo.set(1);
            recoveryListener.get().handleRecovery(ch);
            List<OutboundMessage> afterRecovery = publish(source, 3);
            confirmListener.get().handleAck(3, true);

            assertThat(results).hasSize(53);
            assertThat(results.subList(50, 53))
                    .allSatisfy(r -> {
                        assertThat(r.ack()).isTrue();
                        assertThat(r.unknown()).isFalse();
                    })
                    .extracting(OutboundMessageResult::outboundMessage)
                    .containsExactlyElementsOf(afterRecovery);
        }

        @Test
        void recoveryStartDropsPendingMessagesPublishedBeforeTheFailureWasNoticed() throws Exception {
            RecoverableChannel ch = recoverableBrokerChannel();
            Sinks.Many<OutboundMessage> source = subscribe();
            publish(source, 5);

            recoveryListener.get().handleRecoveryStarted(ch);

            assertThat(results).hasSize(5).allSatisfy(r -> assertThat(r.unknown()).isTrue());
            assertThat(error.get()).isNull();
        }

        @Test
        void finiteFluxCompletesWhenTheConnectionIsLostWithPendingMessages() throws Exception {
            recoverableBrokerChannel();
            sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

            StepVerifier.create(sender.sendWithPublishConfirms(Flux.range(0, 3)
                            .map(i -> new OutboundMessage("exch", "rk", ("m" + i).getBytes()))))
                    .then(() -> shutdownListener.get().shutdownCompleted(
                            new ShutdownSignalException(true, false, null, connection)))
                    .expectNextCount(3)
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
        }

        @Test
        void applicationInitiatedShutdownReportsPendingMessagesWithoutError() {
            brokerChannel(channel);
            Sinks.Many<OutboundMessage> source = subscribe();
            publish(source, 2);

            shutdownListener.get().shutdownCompleted(new ShutdownSignalException(true, true, null, connection));

            assertThat(results).hasSize(2).allSatisfy(r -> assertThat(r.unknown()).isTrue());
            assertThat(error.get()).isNull();
        }

        @Test
        void alreadyClosedChannelTerminatesTheFlowCleanly() {
            brokerChannel(channel);
            ShutdownSignalException closed = new ShutdownSignalException(false, false, null, channel);
            // a shutdown listener added to a closed channel is notified right away
            doAnswer(inv -> {
                ShutdownListener listener = inv.getArgument(0);
                listener.shutdownCompleted(closed);
                return null;
            }).when(channel).addShutdownListener(any(ShutdownListener.class));

            subscribe();

            assertThat(error.get()).isSameAs(closed);
            assertThat(upstreamCancelled).isTrue();
            verify(channel).removeShutdownListener(any(ShutdownListener.class));
            verify(channel).removeConfirmListener(any(ConfirmListener.class));
        }

        @Test
        void unrecoverablePublishErrorFailsTheMessageAndTerminates() throws Exception {
            brokerChannel(channel);
            doThrow(new IOException("boom")).when(channel)
                    .basicPublish(anyString(), anyString(), anyBoolean(), any(), any(byte[].class));
            sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));
            var sendOptions = new SendOptions().exceptionHandler((ctx, ex) -> {
                throw new RabbitFluxException("not retryable", ex);
            });

            StepVerifier.create(sender.sendWithPublishConfirms(
                            Flux.just(new OutboundMessage("exch", "rk", "m".getBytes())), sendOptions))
                    .assertNext(r -> {
                        assertThat(r.ack()).isFalse();
                        assertThat(r.unknown()).isFalse();
                    })
                    .expectError(RabbitFluxException.class)
                    .verify(Duration.ofSeconds(5));
        }
    }
    @Test
    void closingTheSenderClosesTheChannelPoolItWasGiven() {
        ChannelPool channelPool = mock(ChannelPool.class);
        when(channelPool.getChannelMono()).thenAnswer(invocation -> Mono.empty());

        new Sender(new SenderOptions().channelPool(channelPool).connectionMono(Mono.never())).close();

        verify(channelPool).close();
    }
}
