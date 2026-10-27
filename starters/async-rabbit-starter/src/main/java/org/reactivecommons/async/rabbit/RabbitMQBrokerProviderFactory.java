package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageNotifier;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivecommons.async.rabbit.discard.RabbitMQDiscardProviderFactory;
import org.reactivecommons.async.rabbit.discard.RabbitMQDiscardProviderImpl;
import org.reactivecommons.async.rabbit.health.RabbitReactiveHealthIndicator;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.broker.BrokerProviderFactory;
import org.reactivecommons.async.starter.broker.DiscardProvider;
import org.springframework.stereotype.Service;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Service("rabbitmq")
@RequiredArgsConstructor
public class RabbitMQBrokerProviderFactory implements BrokerProviderFactory<AsyncProps> {
    private final BrokerConfig config;
    private final ReactiveReplyRouter router;
    private final RabbitJacksonMessageConverter converter;
    private final MeterRegistry meterRegistry;
    private final CustomReporter errorReporter;
    private final RabbitMQDiscardProviderFactory discardProvider;
    private final UnroutableMessageNotifier unroutableMessageNotifier;
    private final ConnectionFactoryCustomizer cfCustomizer;

    @Override
    public String getBrokerType() {
        return "rabbitmq";
    }

    @Override
    public DiscardProvider getDiscardProvider(AsyncProps props) {
        return discardProvider.build(props, config, converter, cfCustomizer);
    }

    @Override
    public BrokerProvider<AsyncProps> getProvider(String domain, AsyncProps props, DiscardProvider discardProvider) {
        ConnectionFactoryProvider provider = RabbitMQSetupUtils.connectionFactoryProvider(props, cfCustomizer);
        // released when the provider closes: its connections are closed once no live provider uses them
        RabbitMQSetupUtils.retainConnections(provider.getConnectionFactory());
        ReactiveMessageSender sender = RabbitMQSetupUtils.createMessageSender(provider, props, converter,
                unroutableMessageNotifier, meterRegistry, domain);
        ReactiveMessageListener listener = RabbitMQSetupUtils.createMessageListener(provider, props,
                meterRegistry, domain);
        RabbitReactiveHealthIndicator healthIndicator = new RabbitReactiveHealthIndicator(domain,
                () -> RabbitMQSetupUtils.establishedConnection(provider.getConnectionFactory()),
                () -> RabbitMQSetupUtils.establishedListenerConnection(provider.getConnectionFactory()),
                sender::publisherStatus,
                listener.subscriberRegistry()::snapshot,
                props.getBlockedHealthGrace(),
                props.getSubscriberInactiveGrace());
        if (DEFAULT_DOMAIN.equals(domain) && discardProvider instanceof RabbitMQDiscardProviderImpl shared) {
            shared.useSender(sender); // the shared discard notifier publishes through the default domain sender
        }
        DiscardNotifier discardNotifier;
        final Runnable releaseDiscardProvider;
        if (props.isUseDiscardNotifierPerDomain()) {
            discardNotifier = RabbitMQSetupUtils.createDiscardNotifier(sender, props, config, converter);
            releaseDiscardProvider = () -> {
            };
        } else {
            discardNotifier = discardProvider.get();
            releaseDiscardProvider = retain(discardProvider);
        }
        final ConsumerQueueDepth queueDepth = meterRegistry != null
                ? new ConsumerQueueDepth(meterRegistry, domain,
                        RabbitMQSetupUtils.listenerConnectionMono(provider, props.getAppName()),
                        listener.subscriberRegistry()::snapshot)
                : null;
        return new RabbitMQBrokerProvider(domain, props, config, router, converter, meterRegistry, errorReporter,
                healthIndicator, listener, sender, discardNotifier, queueDepth,
                () -> {
                    RabbitMQSetupUtils.releaseConnections(provider.getConnectionFactory());
                    releaseDiscardProvider.run();
                });
    }

    /**
     * Registers the provider as a user of the shared discard provider, which closes the sender it may have created
     * itself once its last user is released.
     *
     * @return releases the provider, when it closes
     */
    private static Runnable retain(DiscardProvider discardProvider) {
        if (discardProvider instanceof RabbitMQDiscardProviderImpl shared) {
            shared.retain();
            return shared::release;
        }
        return () -> {
        };
    }
}
