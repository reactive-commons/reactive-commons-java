package org.reactivecommons.async.rabbit.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.RabbitEDADirectAsyncGateway;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.RabbitDirectAsyncGateway;
import org.reactivecommons.async.rabbit.config.props.BrokerConfigProps;
import org.reactivecommons.async.rabbit.listeners.ApplicationReplyListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Configuration
@Import(RabbitMqConfig.class)
@RequiredArgsConstructor
public class DirectAsyncGatewayConfig {

    private final BrokerConfigProps props;

    @Bean
    public RabbitDirectAsyncGateway rabbitDirectAsyncGateway(BrokerConfig config, ReactiveReplyRouter router, ConnectionManager manager, MessageConverter converter, MeterRegistry meterRegistry) throws Exception {
        return new RabbitEDADirectAsyncGateway(config, router, manager, props.getDirectMessagesExchangeName(), converter, meterRegistry);
    }

    @Bean // TODO: Listen replies from n domains if enabled in config
    public ApplicationReplyListener msgListener(ReactiveReplyRouter router, BrokerConfig config, ConnectionManager manager) {
        final ApplicationReplyListener replyListener = new ApplicationReplyListener(router, manager.getListener(DEFAULT_DOMAIN), props.getReplyQueue());
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
