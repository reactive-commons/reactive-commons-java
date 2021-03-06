package org.reactivecommons.async.rabbit.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.rabbit.HandlerResolver;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.listeners.ApplicationNotificationListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@RequiredArgsConstructor
@Import(RabbitMqConfig.class)
public class NotificacionListenersConfig {

    @Value("${spring.application.name}")
    private String appName;

    private final AsyncProps asyncProps;

    @Bean
    public ApplicationNotificationListener eventNotificationListener(HandlerResolver resolver, MessageConverter messageConverter,
                                                                     ReactiveMessageListener receiver, DiscardNotifier discardNotifier, CustomReporter errorReporter) {
        final ApplicationNotificationListener listener = new ApplicationNotificationListener(
                receiver,
                asyncProps.getDomain().getEvents().getExchange(),
                asyncProps.getNotificationProps().getQueueName(appName),
                resolver,
                messageConverter,
                discardNotifier,
                errorReporter);
        listener.startListener();
        return listener;
    }
}
