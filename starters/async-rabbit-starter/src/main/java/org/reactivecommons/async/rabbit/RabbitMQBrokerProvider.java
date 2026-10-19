package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.java.Log;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.HandlerResolver;
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static reactor.rabbitmq.ExchangeSpecification.exchange;

@Log
public class RabbitMQBrokerProvider implements BrokerProvider<AsyncProps> {

    private final String domain;
    private final AsyncProps props;
    private final BrokerConfig config;
    private final ReactiveReplyRouter router;
    private final RabbitJacksonMessageConverter converter;
    private final MeterRegistry meterRegistry;
    private final CustomReporter errorReporter;
    private final RabbitReactiveHealthIndicator healthIndicator;
    private final ReactiveMessageListener receiver;
    private final ReactiveMessageSender sender;
    private final DiscardNotifier discardNotifier;

    private final List<GenericMessageListener> listeners = new CopyOnWriteArrayList<>();
    private final List<ApplicationReplyListener> replyListeners = new CopyOnWriteArrayList<>();

    public RabbitMQBrokerProvider(String domain, AsyncProps props, BrokerConfig config, ReactiveReplyRouter router,
                                  RabbitJacksonMessageConverter converter, MeterRegistry meterRegistry,
                                  CustomReporter errorReporter, RabbitReactiveHealthIndicator healthIndicator,
                                  ReactiveMessageListener receiver, ReactiveMessageSender sender,
                                  DiscardNotifier discardNotifier) {
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
    }

    public String domain() {
        return domain;
    }

    @Override
    public AsyncProps props() {
        return props;
    }

    public BrokerConfig config() {
        return config;
    }

    public ReactiveReplyRouter router() {
        return router;
    }

    public RabbitJacksonMessageConverter converter() {
        return converter;
    }

    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    public CustomReporter errorReporter() {
        return errorReporter;
    }

    public RabbitReactiveHealthIndicator healthIndicator() {
        return healthIndicator;
    }

    public ReactiveMessageListener receiver() {
        return receiver;
    }

    public ReactiveMessageSender sender() {
        return sender;
    }

    public DiscardNotifier discardNotifier() {
        return discardNotifier;
    }

    @Override
    public DomainEventBus getDomainBus() {
        final String exchangeName = props.getBrokerConfigProps().getDomainEventsExchangeName();
        if (Boolean.TRUE.equals(props.getCreateTopology())) {
            sender.getTopologyCreator().declare(exchange(exchangeName).durable(true).type("topic")).subscribe();
        }
        return new RabbitDomainEventBus(sender, exchangeName, config);
    }

    @Override
    public DirectAsyncGateway getDirectAsyncGateway() {
        String exchangeName = props.getBrokerConfigProps().getDirectMessagesExchangeName();
        if (Boolean.TRUE.equals(props.getCreateTopology())) {
            sender.getTopologyCreator().declare(exchange(exchangeName).durable(true).type("direct")).subscribe();
        }
        listenReplies();
        return new RabbitDirectAsyncGateway(config, router, sender, exchangeName, converter, meterRegistry, domain);
    }

    @Override
    public void listenDomainEvents(HandlerResolver resolver) {
        if (!props.getDomain().isIgnoreThisListener()) {
            final ApplicationEventListener listener = new ApplicationEventListener(receiver,
                    props.getBrokerConfigProps().getEventsQueue(),
                    props.getBrokerConfigProps().getDomainEventsExchangeName(),
                    resolver,
                    converter,
                    props.getWithDLQRetry(),
                    props.getCreateTopology(),
                    props.getMaxRetries(),
                    props.getRetryDelay(),
                    props.getDomain().getEvents().getMaxLengthBytes(),
                    discardNotifier,
                    errorReporter,
                    props.getAppName());
            listeners.add(listener);
            listener.startListener();
        }
    }

    @Override
    public void listenQueues(HandlerResolver resolver) {
        resolver.getQueueListeners().values().forEach(registeredQueueListener -> {
            final ApplicationQueueListener listener = new ApplicationQueueListener(receiver,
                    props.getWithDLQRetry(),
                    props.getMaxRetries(),
                    props.getRetryDelay(),
                    registeredQueueListener,
                    discardNotifier,
                    errorReporter
            );
            listeners.add(listener);
            listener.startListener();
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
                    receiver,
                    props.getBrokerConfigProps().getDomainEventsExchangeName(),
                    props.getBrokerConfigProps().getNotificationsQueue(),
                    props.getCreateTopology(),
                    resolver,
                    converter,
                    discardNotifier,
                    errorReporter);
            listeners.add(listener);
            listener.startListener();
        }
    }

    @Override
    public void listenCommands(HandlerResolver resolver) {
        if (resolver.hasCommandHandlers()) {
            ApplicationCommandListener commandListener = new ApplicationCommandListener(
                    receiver,
                    props.getBrokerConfigProps().getCommandsQueue(),
                    resolver,
                    props.getDirect().getExchange(),
                    converter,
                    props.getWithDLQRetry(),
                    props.getCreateTopology(),
                    props.getDelayedCommands(),
                    props.getMaxRetries(),
                    props.getRetryDelay(),
                    props.getDirect().getMaxLengthBytes(),
                    discardNotifier,
                    errorReporter);

            listeners.add(commandListener);
            commandListener.startListener();
        }
    }

    @Override
    public void listenQueries(HandlerResolver resolver) {
        if (resolver.hasQueryHandlers()) {
            final ApplicationQueryListener listener = new ApplicationQueryListener(
                    receiver,
                    props.getBrokerConfigProps().getQueriesQueue(),
                    resolver,
                    sender,
                    props.getBrokerConfigProps().getDirectMessagesExchangeName(),
                    converter,
                    props.getBrokerConfigProps().getGlobalReplyExchangeName(),
                    props.getWithDLQRetry(),
                    props.getCreateTopology(),
                    props.getMaxRetries(),
                    props.getRetryDelay(),
                    props.getGlobal().getMaxLengthBytes(),
                    props.getDirect().isDiscardTimeoutQueries(),
                    discardNotifier,
                    errorReporter);

            listeners.add(listener);
            listener.startListener();
        }
    }

    @Override
    public void listenReplies() {
        if (Boolean.TRUE.equals(props.getListenReplies())) {
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
     * Stops the listeners and the publisher, and releases their threads. The connection shared by the sender and the
     * listener is not closed here: it is cached per connection factory and shared by every domain.
     */
    @Override
    public void close() {
        listeners.forEach(GenericMessageListener::stop);
        listeners.clear();
        replyListeners.forEach(ApplicationReplyListener::stop);
        replyListeners.clear();
        receiver.receiver().close();
        receiver.topologyCreator().close(); // the listener declares through its own sender
        sender.close();
    }

    @Override
    public Mono<RCHealth> healthCheck() {
        return healthIndicator.health();
    }
}
