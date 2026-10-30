package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.RetryPolicy;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivecommons.async.rabbit.health.RabbitReactiveHealthIndicator;
import org.reactivecommons.async.rabbit.listeners.ApplicationCommandListener;
import org.reactivecommons.async.rabbit.listeners.ApplicationEventListener;
import org.reactivecommons.async.rabbit.listeners.ApplicationNotificationListener;
import org.reactivecommons.async.rabbit.listeners.ApplicationQueryListener;
import org.reactivecommons.async.rabbit.listeners.ApplicationQueueListener;
import org.reactivecommons.async.rabbit.listeners.ApplicationReplyListener;
import org.reactivecommons.async.rabbit.listeners.GenericMessageListener;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.health.RCHealth;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static reactor.rabbitmq.ExchangeSpecification.exchange;

@Accessors(fluent = true)
public class RabbitMQBrokerProvider implements BrokerProvider<AsyncProps> {

    /**
     * How long closing the provider waits for the deliveries in flight to be acknowledged (the counterpart of the
     * {@code shutdownTimeout} of the Spring AMQP listener containers). The same as the time the shared handler pool
     * is given to finish its tasks afterward.
     */
    static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

    private final String domain;
    @Getter
    private final AsyncProps props;
    private final BrokerConfig config;
    private final ReactiveReplyRouter router;
    private final RabbitJacksonMessageConverter converter;
    private final MeterRegistry meterRegistry;
    private final CustomReporter errorReporter;
    private final RabbitReactiveHealthIndicator healthIndicator;
    @Getter
    private final ReactiveMessageListener receiver;
    @Getter
    private final ReactiveMessageSender sender;
    @Getter
    private final DiscardNotifier discardNotifier;
    private final ConsumerQueueDepth queueDepth;
    private final Runnable releaseConnections;
    private final AtomicBoolean closed = new AtomicBoolean();

    private final List<GenericMessageListener> listeners = new CopyOnWriteArrayList<>();
    private boolean started;
    private final List<ApplicationReplyListener> replyListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean listeningReplies = new AtomicBoolean();

    public RabbitMQBrokerProvider(String domain, AsyncProps props, BrokerConfig config, ReactiveReplyRouter router,
                                  RabbitJacksonMessageConverter converter, MeterRegistry meterRegistry,
                                  CustomReporter errorReporter, RabbitReactiveHealthIndicator healthIndicator,
                                  ReactiveMessageListener receiver, ReactiveMessageSender sender,
                                  DiscardNotifier discardNotifier) {
        this(domain, props, config, router, converter, meterRegistry, errorReporter, healthIndicator, receiver,
                sender, discardNotifier, null);
    }

    public RabbitMQBrokerProvider(String domain, AsyncProps props, BrokerConfig config, ReactiveReplyRouter router,
                                  RabbitJacksonMessageConverter converter, MeterRegistry meterRegistry,
                                  CustomReporter errorReporter, RabbitReactiveHealthIndicator healthIndicator,
                                  ReactiveMessageListener receiver, ReactiveMessageSender sender,
                                  DiscardNotifier discardNotifier, ConsumerQueueDepth queueDepth) {
        this(domain, props, config, router, converter, meterRegistry, errorReporter, healthIndicator, receiver,
                sender, discardNotifier, queueDepth, () -> {
                });
    }

    /**
     * @param releaseConnections run once the provider is closed, to release the connections it used
     */
    public RabbitMQBrokerProvider(String domain, AsyncProps props, BrokerConfig config, ReactiveReplyRouter router,
                                  RabbitJacksonMessageConverter converter, MeterRegistry meterRegistry,
                                  CustomReporter errorReporter, RabbitReactiveHealthIndicator healthIndicator,
                                  ReactiveMessageListener receiver, ReactiveMessageSender sender,
                                  DiscardNotifier discardNotifier, ConsumerQueueDepth queueDepth,
                                  Runnable releaseConnections) {
        this.releaseConnections = releaseConnections;
        this.domain = domain;
        this.props = props;
        this.config = config;
        this.router = router;
        this.converter = converter;
        this.meterRegistry = meterRegistry;
        this.errorReporter = errorReporter;
        this.healthIndicator = healthIndicator;
        this.receiver = receiver;
        this.sender = sender;
        this.discardNotifier = discardNotifier;
        this.queueDepth = queueDepth;
    }

    @Override
    public DomainEventBus getDomainBus() {
        final String exchangeName = props.getBrokerConfigProps().getDomainEventsExchangeName();
        if (Boolean.TRUE.equals(props.getCreateTopology())) {
            sender.declareExchange(exchange(exchangeName).durable(true).type("topic"));
        }
        return new RabbitDomainEventBus(sender, exchangeName, config);
    }

    @Override
    public DirectAsyncGateway getDirectAsyncGateway() {
        String exchangeName = props.getBrokerConfigProps().getDirectMessagesExchangeName();
        if (Boolean.TRUE.equals(props.getCreateTopology())) {
            sender.declareExchange(exchange(exchangeName).durable(true).type("direct"));
        }
        listenReplies();
        return new RabbitDirectAsyncGateway(config, router, sender, exchangeName, converter, meterRegistry, domain);
    }

    @Override
    public void listenDomainEvents(HandlerResolver resolver) {
        if (!props.getDomain().isIgnoreThisListener()) {
            final ApplicationEventListener listener = new ApplicationEventListener(listenerContext(resolver),
                    props.getBrokerConfigProps().getEventsQueue(),
                    props.getBrokerConfigProps().getDomainEventsExchangeName(),
                    props.getDomain().getEvents().getMaxLengthBytes(),
                    props.getAppName());
            register(listener);
        }
    }

    @Override
    public void listenQueues(HandlerResolver resolver) {
        resolver.getQueueListeners().values().forEach(registeredQueueListener -> {
            final ApplicationQueueListener listener = new ApplicationQueueListener(listenerContext(resolver),
                    registeredQueueListener);
            register(listener);
        });
    }

    @Override
    public void listenTopics(HandlerResolver resolver) {
        // Not applicable to RabbitMQ: there is no native topic concept. Use listenQueues(...) instead.
    }

    @Override
    public void listenNotificationEvents(HandlerResolver resolver) {
        if (resolver.hasNotificationListeners()) {
            final ApplicationNotificationListener listener = new ApplicationNotificationListener(
                    listenerContext(resolver),
                    props.getBrokerConfigProps().getDomainEventsExchangeName(),
                    props.getBrokerConfigProps().getNotificationsQueue());
            register(listener);
        }
    }

    @Override
    public void listenCommands(HandlerResolver resolver) {
        if (resolver.hasCommandHandlers()) {
            ApplicationCommandListener commandListener = new ApplicationCommandListener(
                    listenerContext(resolver),
                    props.getBrokerConfigProps().getCommandsQueue(),
                    props.getDirect().getExchange(),
                    props.getDelayedCommands(),
                    props.getDirect().getMaxLengthBytes());

            register(commandListener);
        }
    }

    @Override
    public void listenQueries(HandlerResolver resolver) {
        if (resolver.hasQueryHandlers()) {
            final ApplicationQueryListener listener = new ApplicationQueryListener(
                    listenerContext(resolver),
                    props.getBrokerConfigProps().getQueriesQueue(),
                    sender,
                    props.getBrokerConfigProps().getDirectMessagesExchangeName(),
                    props.getBrokerConfigProps().getGlobalReplyExchangeName(),
                    props.getGlobal().getMaxLengthBytes(),
                    props.getDirect().isDiscardTimeoutQueries());

            register(listener);
        }
    }

    /**
     * What the listeners of this domain share, with the retry policy and the topology setting of its properties.
     */
    private ListenerContext<ReactiveMessageListener> listenerContext(HandlerResolver resolver) {
        return new ListenerContext<>(receiver, resolver, converter,
                new RetryPolicy(props.getWithDLQRetry(), props.getMaxRetries(), props.getRetryDelay()),
                props.getCreateTopology(), discardNotifier, errorReporter);
    }

    /**
     * Starts the listener now if the provider was started, otherwise once it is (see {@link #start()}).
     */
    private synchronized void register(GenericMessageListener listener) {
        listeners.add(listener);
        if (started) {
            listener.startListener();
        }
    }

    /**
     * Starts consuming with the listeners registered so far. Called once the application context has created and
     * initialized every bean: a handler never runs while the context is being refreshed (e.g. before a bean warms up a
     * cache, or when the startup fails afterwards). The listener of the replies is not deferred: it starts with the
     * gateway and runs no handler, it only routes the replies to their callers.
     */
    @Override
    public synchronized void start() {
        if (!started) {
            started = true;
            listeners.forEach(GenericMessageListener::startListener);
        }
    }

    /**
     * Starts the listener of the replies once: every call after the first one (e.g. requesting the direct gateway
     * again) would add another consumer to the same exclusive reply queue.
     */
    @Override
    public void listenReplies() {
        if (Boolean.TRUE.equals(props.getListenReplies()) && listeningReplies.compareAndSet(false, true)) {
            final ApplicationReplyListener replyListener = new ApplicationReplyListener(router,
                    receiver,
                    props.getBrokerConfigProps().getReplyQueue(),
                    props.getBrokerConfigProps().getGlobalReplyExchangeName(),
                    props.getCreateTopology()
            );
            replyListeners.add(replyListener);
            replyListener.startListening(config.getRoutingKey());
        }
    }

    /**
     * Stops the listeners and the publisher, and releases their threads. The listeners stop consuming first and their
     * deliveries in flight are given up to {@link #DRAIN_TIMEOUT} to be handled and acknowledged, so they are not
     * redelivered (and handled twice) after the shutdown; the publisher stays open meanwhile for the handlers. The
     * connection shared by the sender and the listener is not closed here: it is cached per connection factory and
     * shared by every domain.
     */
    @Override
    public void close() {
        if (queueDepth != null) {
            queueDepth.close();
        }
        listeners.forEach(GenericMessageListener::stopConsuming);
        final long deadline = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
        listeners.forEach(listener ->
                listener.awaitInFlight(Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))));
        listeners.forEach(GenericMessageListener::stop);
        listeners.clear();
        replyListeners.forEach(ApplicationReplyListener::stop);
        replyListeners.clear();
        receiver.receiver().close();
        receiver.topologyCreator().close(); // the listener declares through its own sender
        sender.close();
        if (closed.compareAndSet(false, true)) {
            releaseConnections.run();
        }
    }

    @Override
    public Mono<RCHealth> healthCheck() {
        return healthIndicator.health();
    }
}
