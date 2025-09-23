package org.reactivecommons.async.rabbit.discard;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.rabbit.ConnectionFactoryCustomizer;
import org.reactivecommons.async.rabbit.RabbitMQSetupUtils;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.starter.broker.DiscardProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class RabbitMQDiscardProviderImpl implements DiscardProvider {
    private final AsyncProps props;
    private final BrokerConfig config;
    private final MessageConverter converter;
    private final ConnectionFactoryCustomizer cfCustomizer;
    private final Map<Boolean, DiscardNotifier> discardNotifier = new ConcurrentHashMap<>();

    @Override
    public DiscardNotifier get() {
        return discardNotifier.computeIfAbsent(true, this::buildDiscardNotifier);
    }

    private DiscardNotifier buildDiscardNotifier(boolean ignored) {
        ConnectionFactoryProvider provider = RabbitMQSetupUtils.connectionFactoryProvider(props, cfCustomizer);
        ReactiveMessageSender sender = RabbitMQSetupUtils.createMessageSender(provider, props, converter, null);
        return RabbitMQSetupUtils.createDiscardNotifier(sender, props, config, converter);
    }
}
