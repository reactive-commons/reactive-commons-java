package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.AllArgsConstructor;
import lombok.Getter;
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
import org.reactivecommons.async.rabbit.listeners.ApplicationReplyListener;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.springframework.boot.actuate.health.Health;
import reactor.core.publisher.Mono;

import static reactor.rabbitmq.ExchangeSpecification.exchange;

@Log
@Getter
@AllArgsConstructor
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

    @Override
    public DomainEventBus getDomainBus() {
        final String exchangeName = props.getBrokerConfigProps().getDomainEventsExchangeName();
        if (props.getCreateTopology()) {
            sender.getTopologyCreator().declare(exchange(exchangeName).durable(true).type("topic")).subscribe();
        }
        return new RabbitDomainEventBus(sender, exchangeName, config);
    }

    @Override
    public DirectAsyncGateway getDirectAsyncGateway(HandlerResolver resolver) {
        String exchangeName = props.getBrokerConfigProps().getDirectMessagesExchangeName();
        if (props.getCreateTopology()) {
            sender.getTopologyCreator().declare(exchange(exchangeName).durable(true).type("direct")).subscribe();
        }
        listenReplies(resolver);
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
    public void listenNotificationEvents(HandlerResolver resolver) {
        if (!resolver.getNotificationListeners().isEmpty()) {
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

    @Override
    public void listenQueries(HandlerResolver resolver) {
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

    @Override
    public void listenReplies(HandlerResolver resolver) {
        if (props.isListenReplies()) {
            final ApplicationReplyListener replyListener =
                    new ApplicationReplyListener(router,
                            receiver,
                            props.getBrokerConfigProps().getReplyQueue(),
                            props.getBrokerConfigProps().getGlobalReplyExchangeName(),
                            props.getCreateTopology());
            replyListener.startListening(config.getRoutingKey());
        }
    }

    @Override
    public Mono<Health> healthCheck() {
        return healthIndicator.health();
    }
}
