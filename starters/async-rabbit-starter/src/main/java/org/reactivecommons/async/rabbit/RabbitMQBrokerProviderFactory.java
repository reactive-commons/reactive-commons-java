package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.AllArgsConstructor;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivecommons.async.rabbit.health.RabbitReactiveHealthIndicator;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.broker.BrokerProviderFactory;
import org.reactivecommons.async.starter.broker.DiscardProvider;
import org.springframework.stereotype.Service;

@Service("rabbitmq")
@AllArgsConstructor
public class RabbitMQBrokerProviderFactory implements BrokerProviderFactory<AsyncProps> {
    private final BrokerConfig config;
    private final ReactiveReplyRouter router;
    private final RabbitJacksonMessageConverter converter;
    private final MeterRegistry meterRegistry;
    private final CustomReporter errorReporter;

    @Override
    public String getBrokerType() {
        return "rabbitmq";
    }

    @Override
    public DiscardProvider getDiscardProvider(AsyncProps props) {
        return new RabbitMQDiscardProvider(props, config, converter);
    }

    @Override
    public BrokerProvider<AsyncProps> getProvider(String domain, AsyncProps props, DiscardProvider discardProvider) {
        RabbitProperties properties = props.getConnectionProperties();
        ConnectionFactoryProvider provider = RabbitMQSetupUtils.connectionFactoryProvider(properties);
        RabbitReactiveHealthIndicator healthIndicator =
                new RabbitReactiveHealthIndicator(domain, provider.getConnectionFactory());
        ReactiveMessageSender sender = RabbitMQSetupUtils.createMessageSender(provider, props, converter);
        ReactiveMessageListener listener = RabbitMQSetupUtils.createMessageListener(provider, props);
        DiscardNotifier discardNotifier;
        if (props.isUseDiscardNotifierPerDomain()) {
            discardNotifier = RabbitMQSetupUtils.createDiscardNotifier(sender, props, config, converter);
        } else {
            discardNotifier = discardProvider.get();
        }
        return new RabbitMQBrokerProvider(domain, props, config, router, converter, meterRegistry, errorReporter,
                healthIndicator, listener, sender, discardNotifier);
    }
}
