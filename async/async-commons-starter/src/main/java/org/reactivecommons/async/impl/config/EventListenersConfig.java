package org.reactivecommons.async.impl.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.impl.DiscardNotifier;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.config.props.AsyncProps;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.impl.ext.CustomErrorReporter;
import org.reactivecommons.async.impl.listeners.ApplicationEventListener;
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
                                                  ReactiveMessageListener receiver, DiscardNotifier discardNotifier, CustomErrorReporter errorReporter) {

        final ApplicationEventListener listener = new ApplicationEventListener(receiver,
                appName + ".subsEvents", resolver, asyncProps.getDomain().getEvents().getExchange(),
                messageConverter, asyncProps.getWithDLQRetry(), asyncProps.getMaxRetries(), asyncProps.getRetryDelay(),asyncProps.getDomain().getEvents().getMaxLengthBytes(),
                discardNotifier, errorReporter, appName);

        listener.startListener();

        return listener;
    }
}
