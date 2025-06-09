package org.reactivecommons.async.rabbit.discard;

import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.starter.broker.DiscardProvider;

public interface RabbitMQDiscardProviderFactory {
    DiscardProvider build(AsyncProps props, BrokerConfig config, MessageConverter converter);
}
