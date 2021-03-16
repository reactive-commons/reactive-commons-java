package org.reactivecommons.async.impl.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.impl.RabbitDirectAsyncGateway;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.config.props.BrokerConfigProps;
import org.reactivecommons.async.parent.converters.MessageConverter;
import org.reactivecommons.async.impl.listeners.ApplicationReplyListener;
import org.reactivecommons.async.parent.config.BrokerConfig;
import org.reactivecommons.async.parent.reply.ReactiveReplyRouter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(RabbitMqConfig.class)
@RequiredArgsConstructor
public class DirectAsyncGatewayConfig {

    private final BrokerConfigProps props;

    @Bean
    public RabbitDirectAsyncGateway rabbitDirectAsyncGateway(BrokerConfig config, ReactiveReplyRouter router, ReactiveMessageSender rSender, MessageConverter converter) throws Exception {
        return new RabbitDirectAsyncGateway(config, router, rSender, props.getDirectMessagesExchangeName(), converter);
    }

    @Bean
    public ApplicationReplyListener msgListener(ReactiveReplyRouter router, BrokerConfig config, ReactiveMessageListener listener)  {
        final ApplicationReplyListener replyListener = new ApplicationReplyListener(router, listener, props.getReplyQueue());
        replyListener.startListening(config.getRoutingKey());
        return replyListener;
    }


    @Bean
    public ReactiveReplyRouter router() {
        return new ReactiveReplyRouter();
    }


}
