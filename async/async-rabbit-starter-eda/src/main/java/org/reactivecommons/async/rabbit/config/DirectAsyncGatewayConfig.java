package org.reactivecommons.async.rabbit.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.RabbitEDADirectAsyncGateway;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.RabbitDirectAsyncGateway;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.BrokerConfigProps;
import org.reactivecommons.async.rabbit.listeners.ApplicationReplyListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.atomic.AtomicReference;

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

    @Bean
    public ApplicationReplyListener msgListener(ReactiveReplyRouter router, AsyncProps asyncProps, BrokerConfig config, ConnectionManager manager) {
        asyncProps.getListenRepliesFrom().add(DEFAULT_DOMAIN);
        AtomicReference<ApplicationReplyListener> localListener = new AtomicReference<>();

        asyncProps.getListenRepliesFrom().forEach(domain -> {

            final ApplicationReplyListener replyListener = new ApplicationReplyListener(router, manager.getListener(domain), props.getReplyQueue());
            replyListener.startListening(config.getRoutingKey());

            if (DEFAULT_DOMAIN.equals(domain)) {
                localListener.set(replyListener);
            }
        });

        return localListener.get();
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
