/*
 * Copyright (c) 2017-2021 VMware Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmListener;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ReturnListener;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.impl.AMQImpl;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static reactor.rabbitmq.Helpers.safelyExecute;

/**
 * Reactive abstraction to create resources and send messages.
 */
public class Sender implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Sender.class);

    private static final Function<Connection, Channel> CHANNEL_CREATION_FUNCTION = conn -> {
        try {
            return conn.createChannel();
        } catch (IOException e) {
            throw new RabbitFluxException("Error while creating channel", e);
        }
    };

    private static final Function<Connection, Channel> CHANNEL_PROXY_CREATION_FUNCTION = conn -> {
        try {
            return ChannelProxy.create(conn);
        } catch (IOException e) {
            throw new RabbitFluxException("Error while creating channel", e);
        }
    };

    private final Mono<? extends Connection> connectionMono;

    private final Mono<? extends Channel> channelMono;

    private final BiConsumer<SignalType, Channel> channelCloseHandler;

    private final AtomicReference<Connection> connection = new AtomicReference<>();

    private final Mono<? extends Channel> resourceManagementChannelMono;

    private final Scheduler resourceManagementScheduler;

    private final boolean privateResourceManagementScheduler;

    private final Scheduler connectionSubscriptionScheduler;

    private final boolean privateConnectionSubscriptionScheduler;

    private final ExecutorService channelCloseThreadPool = Executors.newCachedThreadPool();

    private final int connectionClosingTimeout;

    private final AtomicBoolean closingOrClosed = new AtomicBoolean(false);

    private static final String REACTOR_RABBITMQ_DELIVERY_TAG_HEADER = "reactor_rabbitmq_delivery_tag";

    public Sender() {
        this(new SenderOptions());
    }

    public Sender(SenderOptions options) {
        this.privateConnectionSubscriptionScheduler = options.getConnectionSubscriptionScheduler() == null;
        this.connectionSubscriptionScheduler = options.getConnectionSubscriptionScheduler() == null
                ? createScheduler("rabbitmq-sender-connection-subscription")
                : options.getConnectionSubscriptionScheduler();

        Mono<? extends Connection> cm;
        if (options.getConnectionMono() == null) {
            cm = Mono.fromCallable(() -> {
                if (options.getConnectionSupplier() == null) {
                    return options.getConnectionFactory().newConnection();
                } else {
                    return options.getConnectionSupplier().apply(null);
                }
            });
            cm = options.getConnectionMonoConfigurator().apply(cm);
            cm = cm.doOnNext(connection::set)
                    .subscribeOn(this.connectionSubscriptionScheduler)
                    .transform(this::cache);
        } else {
            cm = options.getConnectionMono();
        }

        this.connectionMono = cm;
        this.channelMono = options.getChannelMono();
        this.channelCloseHandler = options.getChannelCloseHandler() == null
                ? ChannelCloseHandlers.SENDER_CHANNEL_CLOSE_HANDLER_INSTANCE
                : options.getChannelCloseHandler();
        this.privateResourceManagementScheduler = options.getResourceManagementScheduler() == null;
        this.resourceManagementScheduler = options.getResourceManagementScheduler() == null
                ? createScheduler("rabbitmq-sender-resource-creation")
                : options.getResourceManagementScheduler();
        this.resourceManagementChannelMono = options.getResourceManagementChannelMono() == null
                ? connectionMono.map(CHANNEL_PROXY_CREATION_FUNCTION).transform(this::cache)
                : options.getResourceManagementChannelMono();
        if (options.getConnectionClosingTimeout() != null && !Duration.ZERO.equals(options.getConnectionClosingTimeout())) {
            this.connectionClosingTimeout = (int) options.getConnectionClosingTimeout().toMillis();
        } else {
            this.connectionClosingTimeout = -1;
        }
    }

    protected Scheduler createScheduler(String name) {
        return Schedulers.newBoundedElastic(
                Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE,
                Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
                name
        );
    }

    protected <T> Mono<T> cache(Mono<T> mono) {
        return Utils.cache(mono);
    }

    // --- Send ---

    public Mono<Void> send(Publisher<OutboundMessage> messages) {
        return send(messages, new SendOptions());
    }

    public Mono<Void> send(Publisher<OutboundMessage> messages, SendOptions options) {
        var opts = options == null ? new SendOptions() : options;
        final Mono<? extends Channel> currentChannelMono = getChannelMono(opts);
        final BiConsumer<SendContext, Exception> exceptionHandler = opts.getExceptionHandler();
        final BiConsumer<SignalType, Channel> closeHandler = getChannelCloseHandler(opts);

        return currentChannelMono.flatMapMany(channel ->
                Flux.from(messages)
                        .doOnNext(message -> {
                            try {
                                channel.basicPublish(
                                        message.getExchange(),
                                        message.getRoutingKey(),
                                        message.getProperties(),
                                        message.getBody()
                                );
                            } catch (Exception e) {
                                exceptionHandler.accept(new SendContext<>(channel, message), e);
                            }
                        })
                        .doOnError(e -> LOGGER.warn("Send failed with exception", e))
                        .doFinally(st -> closeHandler.accept(st, channel))
        ).then();
    }

    // --- Publish Confirms ---

    public Flux<OutboundMessageResult> sendWithPublishConfirms(Publisher<OutboundMessage> messages) {
        return sendWithPublishConfirms(messages, new SendOptions());
    }

    public Flux<OutboundMessageResult> sendWithPublishConfirms(Publisher<OutboundMessage> messages, SendOptions options) {
        return Flux.from(sendWithTypedPublishConfirms(messages, options));
    }

    public <OMSG extends OutboundMessage> Flux<OutboundMessageResult<OMSG>> sendWithTypedPublishConfirms(Publisher<OMSG> messages) {
        return sendWithTypedPublishConfirms(messages, new SendOptions());
    }

    public <OMSG extends OutboundMessage> Flux<OutboundMessageResult<OMSG>> sendWithTypedPublishConfirms(
            Publisher<OMSG> messages, SendOptions options) {
        var sendOptions = options == null ? new SendOptions() : options;
        final Mono<? extends Channel> currentChannelMono = getChannelMono(sendOptions);
        final BiConsumer<SignalType, Channel> closeHandler = getChannelCloseHandler(sendOptions);

        Flux<OutboundMessageResult<OMSG>> result = currentChannelMono.map(channel -> {
            try {
                channel.confirmSelect();
            } catch (IOException e) {
                throw new RabbitFluxException("Error while setting publisher confirms on channel", e);
            }
            return channel;
        }).flatMapMany(channel -> new PublishConfirmOperator<>(messages, channel, sendOptions).doFinally(signalType -> {
            if (signalType == SignalType.ON_ERROR) {
                closeHandler.accept(signalType, channel);
            } else {
                channelCloseThreadPool.execute(() -> closeHandler.accept(signalType, channel));
            }
        }));

        if (sendOptions.getMaxInFlight() != null) {
            result = result.publishOn(sendOptions.getScheduler(), sendOptions.getMaxInFlight());
        }
        return result;
    }

    // package-protected for testing
    Mono<? extends Channel> getChannelMono(SendOptions options) {
        return Stream.of(options.getChannelMono(), channelMono)
                .filter(Objects::nonNull)
                .findFirst().orElse(connectionMono.map(CHANNEL_CREATION_FUNCTION));
    }

    private BiConsumer<SignalType, Channel> getChannelCloseHandler(SendOptions options) {
        return options.getChannelCloseHandler() != null
                ? options.getChannelCloseHandler() : this.channelCloseHandler;
    }

    // --- Resource Management (declare / bind / unbind) ---

    public Mono<AMQP.Queue.DeclareOk> declare(QueueSpecification specification) {
        AMQP.Queue.Declare declare;
        if (specification.getName() == null) {
            declare = new AMQImpl.Queue.Declare.Builder()
                    .queue("")
                    .durable(false)
                    .exclusive(true)
                    .autoDelete(true)
                    .arguments(specification.getArguments())
                    .build();
        } else {
            declare = new AMQImpl.Queue.Declare.Builder()
                    .queue(specification.getName())
                    .durable(specification.isDurable())
                    .exclusive(specification.isExclusive())
                    .autoDelete(specification.isAutoDelete())
                    .passive(specification.isPassive())
                    .arguments(specification.getArguments())
                    .build();
        }

        return resourceManagementChannelMono.map(channel -> {
                    try {
                        return channel.asyncCompletableRpc(declare);
                    } catch (IOException e) {
                        throw new RabbitFluxException("Error during RPC call", e);
                    }
                }).flatMap(Mono::fromCompletionStage)
                .map(command -> (AMQP.Queue.DeclareOk) command.getMethod())
                .publishOn(resourceManagementScheduler);
    }

    public Mono<AMQP.Exchange.DeclareOk> declare(ExchangeSpecification specification) {
        AMQP.Exchange.Declare declare = new AMQImpl.Exchange.Declare.Builder()
                .exchange(specification.getName())
                .type(specification.getType())
                .durable(specification.isDurable())
                .autoDelete(specification.isAutoDelete())
                .internal(specification.isInternal())
                .passive(specification.isPassive())
                .arguments(specification.getArguments())
                .build();

        return resourceManagementChannelMono.map(channel -> {
                    try {
                        return channel.asyncCompletableRpc(declare);
                    } catch (IOException e) {
                        throw new RabbitFluxException("Error during RPC call", e);
                    }
                }).flatMap(Mono::fromCompletionStage)
                .map(command -> (AMQP.Exchange.DeclareOk) command.getMethod())
                .publishOn(resourceManagementScheduler);
    }

    public Mono<AMQP.Queue.BindOk> bind(BindingSpecification specification) {
        AMQP.Queue.Bind binding = new AMQImpl.Queue.Bind.Builder()
                .exchange(specification.getExchange())
                .queue(specification.getQueue())
                .routingKey(specification.getRoutingKey())
                .arguments(specification.getArguments())
                .build();

        return resourceManagementChannelMono.map(channel -> {
                    try {
                        return channel.asyncCompletableRpc(binding);
                    } catch (IOException e) {
                        throw new RabbitFluxException("Error during RPC call", e);
                    }
                }).flatMap(Mono::fromCompletionStage)
                .map(command -> (AMQP.Queue.BindOk) command.getMethod())
                .publishOn(resourceManagementScheduler);
    }

    public Mono<AMQP.Queue.UnbindOk> unbind(BindingSpecification specification) {
        AMQP.Queue.Unbind unbinding = new AMQImpl.Queue.Unbind.Builder()
                .exchange(specification.getExchange())
                .queue(specification.getQueue())
                .routingKey(specification.getRoutingKey())
                .arguments(specification.getArguments())
                .build();

        return resourceManagementChannelMono.map(channel -> {
                    try {
                        return channel.asyncCompletableRpc(unbinding);
                    } catch (IOException e) {
                        throw new RabbitFluxException("Error during RPC call", e);
                    }
                }).flatMap(Mono::fromCompletionStage)
                .map(command -> (AMQP.Queue.UnbindOk) command.getMethod())
                .publishOn(resourceManagementScheduler);
    }

    // --- Lifecycle ---

    @Override
    public void close() {
        if (closingOrClosed.compareAndSet(false, true)) {
            if (connection.get() != null) {
                safelyExecute(LOGGER, () -> connection.get().close(this.connectionClosingTimeout),
                        "Error while closing sender connection");
            }
            if (this.privateConnectionSubscriptionScheduler) {
                safelyExecute(LOGGER, this.connectionSubscriptionScheduler::dispose,
                        "Error while disposing connection subscription scheduler");
            }
            if (this.privateResourceManagementScheduler) {
                safelyExecute(LOGGER, this.resourceManagementScheduler::dispose,
                        "Error while disposing resource management scheduler");
            }
            safelyExecute(LOGGER, channelCloseThreadPool::shutdown,
                    "Error while closing channel closing thread pool");
        }
    }

    // --- Inner Classes ---

    public static class SendContext<OMSG extends OutboundMessage> {

        protected final Channel channel;
        protected final OMSG message;

        protected SendContext(Channel channel, OMSG message) {
            this.channel = channel;
            this.message = message;
        }

        public OMSG getMessage() {
            return message;
        }

        public Channel getChannel() {
            return channel;
        }

        public void publish(OutboundMessage outboundMessage) throws Exception {
            this.channel.basicPublish(
                    outboundMessage.getExchange(),
                    outboundMessage.getRoutingKey(),
                    outboundMessage.getProperties(),
                    outboundMessage.getBody()
            );
        }

        public void publish() throws Exception {
            this.publish(getMessage());
        }
    }

    public static class ConfirmSendContext<OMSG extends OutboundMessage> extends SendContext<OMSG> {

        private final PublishConfirmSubscriber<OMSG> subscriber;

        protected ConfirmSendContext(Channel channel, OMSG message, PublishConfirmSubscriber<OMSG> subscriber) {
            super(channel, message);
            this.subscriber = subscriber;
        }

        @Override
        public void publish(OutboundMessage outboundMessage) throws Exception {
            long nextPublishSeqNo = channel.getNextPublishSeqNo();
            try {
                subscriber.unconfirmed.putIfAbsent(nextPublishSeqNo, this.message);
                this.channel.basicPublish(
                        outboundMessage.getExchange(),
                        outboundMessage.getRoutingKey(),
                        this.subscriber.trackReturned,
                        this.subscriber.propertiesProcessor.apply(message.getProperties(), nextPublishSeqNo),
                        outboundMessage.getBody()
                );
            } catch (Exception e) {
                subscriber.unconfirmed.remove(nextPublishSeqNo);
                throw e;
            }
        }

        @Override
        public void publish() throws Exception {
            this.publish(getMessage());
        }
    }

    private static class PublishConfirmOperator<OMSG extends OutboundMessage>
            extends FluxOperator<OMSG, OutboundMessageResult<OMSG>> {

        private final Channel channel;
        private final SendOptions options;

        PublishConfirmOperator(Publisher<OMSG> source, Channel channel, SendOptions options) {
            super(Flux.from(source));
            this.channel = channel;
            this.options = options;
        }

        @Override
        public void subscribe(CoreSubscriber<? super OutboundMessageResult<OMSG>> actual) {
            source.subscribe(new PublishConfirmSubscriber<>(channel, actual, options));
        }
    }

    static class PublishConfirmSubscriber<OMSG extends OutboundMessage> implements
            CoreSubscriber<OMSG>, Subscription {

        private final AtomicReference<SubscriberState> state = new AtomicReference<>(SubscriberState.INIT);
        private final AtomicReference<Throwable> firstException = new AtomicReference<>();
        final ConcurrentNavigableMap<Long, OMSG> unconfirmed = new ConcurrentSkipListMap<>();

        private final Channel channel;
        private final Subscriber<? super OutboundMessageResult<OMSG>> subscriber;
        private final BiConsumer<SendContext, Exception> exceptionHandler;

        private Subscription subscription;
        private ConfirmListener confirmListener;
        private ReturnListener returnListener;
        private ShutdownListener shutdownListener;

        final boolean trackReturned;
        final BiFunction<AMQP.BasicProperties, Long, AMQP.BasicProperties> propertiesProcessor;

        PublishConfirmSubscriber(Channel channel, Subscriber<? super OutboundMessageResult<OMSG>> subscriber,
                                 SendOptions options) {
            this.channel = channel;
            this.subscriber = subscriber;
            this.exceptionHandler = options.getExceptionHandler();
            this.trackReturned = options.isTrackReturned();
            this.propertiesProcessor = this.trackReturned
                    ? PublishConfirmSubscriber::addReactorRabbitMQDeliveryTag
                    : (properties, deliveryTag) -> properties;
        }

        @Override
        public void request(long n) {
            subscription.request(n);
        }

        @Override
        public void cancel() {
            subscription.cancel();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            if (Operators.validate(this.subscription, subscription)) {

                if (this.trackReturned) {
                    this.returnListener = (replyCode, replyText, exchange, routingKey, properties, body) -> {
                        try {
                            Object deliveryTagObj = properties.getHeaders().get(REACTOR_RABBITMQ_DELIVERY_TAG_HEADER);
                            if (deliveryTagObj instanceof Long deliveryTag) {
                                OMSG outboundMessage = unconfirmed.get(deliveryTag);
                                subscriber.onNext(new OutboundMessageResult<>(outboundMessage, true, true));
                                unconfirmed.remove(deliveryTag);
                            } else {
                                handleError(new IllegalArgumentException(
                                        "Missing header " + REACTOR_RABBITMQ_DELIVERY_TAG_HEADER), null);
                            }
                        } catch (Exception e) {
                            handleError(e, null);
                        }
                    };
                    channel.addReturnListener(this.returnListener);
                }

                this.confirmListener = new ConfirmListener() {

                    @Override
                    public void handleAck(long deliveryTag, boolean multiple) {
                        handleAckNack(deliveryTag, multiple, true);
                    }

                    @Override
                    public void handleNack(long deliveryTag, boolean multiple) {
                        handleAckNack(deliveryTag, multiple, false);
                    }

                    private void handleAckNack(long deliveryTag, boolean multiple, boolean ack) {
                        if (multiple) {
                            try {
                                var unconfirmedToSend = unconfirmed.headMap(deliveryTag, true);
                                var iterator = unconfirmedToSend.entrySet().iterator();
                                while (iterator.hasNext()) {
                                    subscriber.onNext(new OutboundMessageResult<>(
                                            iterator.next().getValue(), ack, false));
                                    iterator.remove();
                                }
                            } catch (Exception e) {
                                handleError(e, null);
                            }
                        } else {
                            OMSG outboundMessage = unconfirmed.get(deliveryTag);
                            if (outboundMessage != null) {
                                try {
                                    unconfirmed.remove(deliveryTag);
                                    subscriber.onNext(new OutboundMessageResult<>(outboundMessage, ack, false));
                                } catch (Exception e) {
                                    handleError(e, new OutboundMessageResult<>(outboundMessage, ack, false));
                                }
                            }
                        }
                        if (unconfirmed.isEmpty()) {
                            maybeComplete();
                        }
                    }
                };
                channel.addConfirmListener(confirmListener);

                this.shutdownListener = sse -> {
                    var iterator = this.unconfirmed.entrySet().iterator();
                    while (iterator.hasNext()) {
                        OMSG message = iterator.next().getValue();
                        if (!message.isPublished()) {
                            try {
                                subscriber.onNext(new OutboundMessageResult<>(message, false, false));
                                iterator.remove();
                            } catch (Exception e) {
                                LOGGER.info("Error while nacking messages after channel failure");
                            }
                        }
                    }
                    if (!sse.isHardError() && !sse.isInitiatedByApplication()) {
                        subscriber.onError(sse);
                    }
                };
                channel.addShutdownListener(shutdownListener);

                state.set(SubscriberState.ACTIVE);
                this.subscription = subscription;
                subscriber.onSubscribe(this);
            }
        }

        @Override
        public void onNext(OMSG message) {
            if (checkComplete(message)) {
                return;
            }
            long nextPublishSeqNo = channel.getNextPublishSeqNo();
            try {
                unconfirmed.putIfAbsent(nextPublishSeqNo, message);
                channel.basicPublish(
                        message.getExchange(),
                        message.getRoutingKey(),
                        this.trackReturned,
                        this.propertiesProcessor.apply(message.getProperties(), nextPublishSeqNo),
                        message.getBody()
                );
                message.published();
            } catch (Exception e) {
                unconfirmed.remove(nextPublishSeqNo);
                try {
                    this.exceptionHandler.accept(new ConfirmSendContext<>(channel, message, this), e);
                } catch (RabbitFluxRetryTimeoutException timeoutException) {
                    subscriber.onNext(new OutboundMessageResult<>(message, false, false));
                } catch (Exception innerException) {
                    handleError(innerException, new OutboundMessageResult<>(message, false, false));
                }
            }
        }

        private static AMQP.BasicProperties addReactorRabbitMQDeliveryTag(
                AMQP.BasicProperties properties, long deliveryTag) {
            var baseProperties = properties != null ? properties : new AMQP.BasicProperties();
            var headers = baseProperties.getHeaders() != null
                    ? new HashMap<>(baseProperties.getHeaders()) : new HashMap<String, Object>();
            headers.putIfAbsent(REACTOR_RABBITMQ_DELIVERY_TAG_HEADER, deliveryTag);
            return baseProperties.builder().headers(headers).build();
        }

        @Override
        public void onError(Throwable throwable) {
            if (state.compareAndSet(SubscriberState.ACTIVE, SubscriberState.COMPLETE)
                    || state.compareAndSet(SubscriberState.OUTBOUND_DONE, SubscriberState.COMPLETE)) {
                channel.removeConfirmListener(confirmListener);
                channel.removeShutdownListener(shutdownListener);
                if (returnListener != null) {
                    channel.removeReturnListener(returnListener);
                }
                subscriber.onError(throwable);
            } else if (firstException.compareAndSet(null, throwable) && state.get() == SubscriberState.COMPLETE) {
                Operators.onErrorDropped(throwable, currentContext());
            }
        }

        @Override
        public void onComplete() {
            if (state.compareAndSet(SubscriberState.ACTIVE, SubscriberState.OUTBOUND_DONE)
                    && unconfirmed.isEmpty()) {
                maybeComplete();
            }
        }

        private void handleError(Exception e, OutboundMessageResult<OMSG> result) {
            LOGGER.error("error in publish confirm sending", e);
            boolean complete = checkComplete(e);
            firstException.compareAndSet(null, e);
            if (!complete) {
                if (result != null) {
                    subscriber.onNext(result);
                }
                onError(e);
            }
        }

        private void maybeComplete() {
            if (state.compareAndSet(SubscriberState.OUTBOUND_DONE, SubscriberState.COMPLETE)) {
                channel.removeConfirmListener(confirmListener);
                channel.removeShutdownListener(shutdownListener);
                if (returnListener != null) {
                    channel.removeReturnListener(returnListener);
                }
                subscriber.onComplete();
            }
        }

        <T> boolean checkComplete(T t) {
            boolean complete = state.get() == SubscriberState.COMPLETE;
            if (complete && firstException.get() == null) {
                Operators.onNextDropped(t, currentContext());
            }
            return complete;
        }
    }

}
