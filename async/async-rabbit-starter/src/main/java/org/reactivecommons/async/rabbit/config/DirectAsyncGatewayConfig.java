package org.reactivecommons.async.rabbit.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.RabbitDirectAsyncGateway;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.listeners.ApplicationReplyListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.logging.Level;

@Log
@Configuration
@Import(RabbitMqConfig.class)
@RequiredArgsConstructor
public class DirectAsyncGatewayConfig {

    private final IBrokerConfigProps brokerConfigProps;

    @Bean
    public RabbitDirectAsyncGateway rabbitDirectAsyncGateway(BrokerConfig config, ReactiveReplyRouter router,
                                                             ReactiveMessageSender rSender, MessageConverter converter,
                                                             MeterRegistry meterRegistry) {
        return new RabbitDirectAsyncGateway(config, router, rSender, brokerConfigProps.getDirectMessagesExchangeName(),
                converter, meterRegistry);
    }

    @Bean
    public ApplicationReplyListener msgListener(ReactiveReplyRouter router, BrokerConfig config, AsyncProps asyncProps,
                                                ReactiveMessageListener listener) {
        if (!asyncProps.isListenReplies()) {
            log.log(Level.WARNING, "ApplicationReplyListener is disabled in AsyncProps or app.async.listenReplies");
            return null;
        }
        final ApplicationReplyListener replyListener = new ApplicationReplyListener(router, listener,
                brokerConfigProps.getReplyQueue(), brokerConfigProps.getGlobalReplyExchangeName(),
                asyncProps.getCreateTopology());
        replyListener.startListening(config.getRoutingKey());
        return replyListener;
    }

    @Bean
    public ReactiveReplyRouter router() {
        return new ReactiveReplyRouter();
    }

    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    public MeterRegistry defaultRabbitMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}
