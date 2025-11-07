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
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.health.RCHealth;
import reactor.core.publisher.Mono;

import static reactor.rabbitmq.ExchangeSpecification.exchange;

@Log
public record RabbitMQBrokerProvider(String domain,
                                     AsyncProps props,
                                     BrokerConfig config,
                                     ReactiveReplyRouter router,
                                     RabbitJacksonMessageConverter converter,
                                     MeterRegistry meterRegistry,
                                     CustomReporter errorReporter,
                                     RabbitReactiveHealthIndicator healthIndicator,
                                     ReactiveMessageListener receiver,
                                     ReactiveMessageSender sender,
                                     DiscardNotifier discardNotifier) implements BrokerProvider<AsyncProps> {

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
        return new RabbitDirectAsyncGateway(config, router, sender, exchangeName, converter, meterRegistry);
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
            listener.startListener();
        }
    }

    @Override
    public void listenQueues(HandlerResolver resolver) {
        resolver.getQueueListeners().values().forEach(registeredQueueListener ->
                new ApplicationQueueListener(receiver,
                props.getWithDLQRetry(),
                props.getMaxRetries(),
                props.getRetryDelay(),
                registeredQueueListener,
                discardNotifier,
                errorReporter
        ).startListener());
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
            replyListener.startListening(config.getRoutingKey());
        }
    }

    @Override
    public Mono<RCHealth> healthCheck() {
        return healthIndicator.health();
    }
}
