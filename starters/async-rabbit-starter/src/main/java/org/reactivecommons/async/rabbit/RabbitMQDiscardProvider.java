package org.reactivecommons.async.rabbit;

import lombok.AllArgsConstructor;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.starter.broker.DiscardProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@AllArgsConstructor
public class RabbitMQDiscardProvider implements DiscardProvider {
    private final AsyncProps props;
    private final BrokerConfig config;
    private final MessageConverter converter;
    private final Map<Boolean, DiscardNotifier> discardNotifier = new ConcurrentHashMap<>();

    @Override
    public DiscardNotifier get() {
        return discardNotifier.computeIfAbsent(true, this::buildDiscardNotifier);
    }

    private DiscardNotifier buildDiscardNotifier(boolean ignored) {
        RabbitProperties properties = props.getConnectionProperties();
        ConnectionFactoryProvider provider = RabbitMQSetupUtils.connectionFactoryProvider(properties);
        ReactiveMessageSender sender = RabbitMQSetupUtils.createMessageSender(provider, props, converter);
        return RabbitMQSetupUtils.createDiscardNotifier(sender, props, config, converter);
    }
}
