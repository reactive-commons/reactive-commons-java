package org.reactivecommons.async.rabbit.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.RabbitEDADirectAsyncGateway;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.RabbitDirectAsyncGateway;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.rabbit.listeners.ApplicationReplyListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;
import static reactor.rabbitmq.ExchangeSpecification.exchange;

@Log
@Configuration
@Import(RabbitMqConfig.class)
@RequiredArgsConstructor
public class DirectAsyncGatewayConfig {

    @Bean
    public RabbitDirectAsyncGateway rabbitDirectAsyncGateway(BrokerConfig config, ReactiveReplyRouter router,
                                                             ConnectionManager manager, MessageConverter converter,
                                                             MeterRegistry meterRegistry,
                                                             AsyncPropsDomain asyncPropsDomain) {
        ReactiveMessageSender sender = manager.getSender(DEFAULT_DOMAIN);
        AsyncProps asyncProps = asyncPropsDomain.getProps(DEFAULT_DOMAIN);
        String exchangeName = asyncProps.getBrokerConfigProps().getDirectMessagesExchangeName();
        if (asyncProps.getCreateTopology()) {
            sender.getTopologyCreator().declare(exchange(exchangeName).durable(true).type("direct")).subscribe();
        }
        return new RabbitEDADirectAsyncGateway(config, router, manager, exchangeName, converter, meterRegistry);
    }

    @Bean
    public ApplicationReplyListener msgListener(ReactiveReplyRouter router, AsyncPropsDomain asyncProps,
                                                BrokerConfig config, ConnectionManager manager) {
        AtomicReference<ApplicationReplyListener> localListener = new AtomicReference<>();

        asyncProps.forEach((domain, props) -> {
            if (props.isListenReplies()) {
                final ApplicationReplyListener replyListener =
                        new ApplicationReplyListener(router, manager.getListener(domain),
                                props.getBrokerConfigProps().getReplyQueue(),
                                props.getBrokerConfigProps().getGlobalReplyExchangeName(), props.getCreateTopology());
                replyListener.startListening(config.getRoutingKey());

                if (DEFAULT_DOMAIN.equals(domain)) {
                    localListener.set(replyListener);
                }
            } else {
                log.log(Level.WARNING,"ApplicationReplyListener is disabled in AsyncProps or app.async." + domain
                        + ".listenReplies for domain " + domain);
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
