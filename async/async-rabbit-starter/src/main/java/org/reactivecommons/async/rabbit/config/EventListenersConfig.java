package org.reactivecommons.async.rabbit.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.HandlerResolver;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.listeners.ApplicationEventListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@RequiredArgsConstructor
@Import(RabbitMqConfig.class)
public class EventListenersConfig {

    @Value("${spring.application.name}")
    private String appName;

    private final AsyncProps asyncProps;

    @Bean
    public ApplicationEventListener eventListener(HandlerResolver resolver, MessageConverter messageConverter,
                                                  ReactiveMessageListener receiver, DiscardNotifier discardNotifier,
                                                  IBrokerConfigProps brokerConfigProps, CustomReporter errorReporter) {

        final ApplicationEventListener listener = new ApplicationEventListener(receiver,
                brokerConfigProps.getEventsQueue(), brokerConfigProps.getDomainEventsExchangeName(), resolver,
                messageConverter, asyncProps.getWithDLQRetry(), asyncProps.getCreateTopology(),
                asyncProps.getMaxRetries(), asyncProps.getRetryDelay(),
                asyncProps.getDomain().getEvents().getMaxLengthBytes(), discardNotifier, errorReporter, appName);

        listener.startListener();

        return listener;
    }
}
