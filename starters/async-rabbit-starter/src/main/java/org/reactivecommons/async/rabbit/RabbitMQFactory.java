package org.reactivecommons.async.rabbit;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;

@RequiredArgsConstructor
public class RabbitMQFactory {

    private final RabbitJacksonMessageConverter converter;

    public ReactiveMessageSender createMessageSender(AsyncProps props) {
        var connectionFactoryProvider = RabbitMQSetupUtils.connectionFactoryProvider(props.getConnectionProperties());
        return RabbitMQSetupUtils.createMessageSender(connectionFactoryProvider, props, converter, null);
    }
}
