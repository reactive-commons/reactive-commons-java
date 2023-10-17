package org.reactivecommons.async.rabbit.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.listeners.ApplicationNotificationListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Configuration
@RequiredArgsConstructor
@Import(RabbitMqConfig.class)
public class NotificacionListenersConfig {

    @Value("${spring.application.name}")
    private String appName;

    private final AsyncProps asyncProps;

    @Bean
    public ApplicationNotificationListener eventNotificationListener(ConnectionManager manager,
                                                                     DomainHandlers handlers,
                                                                     MessageConverter messageConverter,
                                                                     CustomReporter errorReporter) {
        final ApplicationNotificationListener listener = new ApplicationNotificationListener(
                manager.getListener(DEFAULT_DOMAIN),
                asyncProps.getDomain().getEvents().getExchange(),
                asyncProps.getNotificationProps().getQueueName(appName),
                handlers.get(DEFAULT_DOMAIN),
                messageConverter,
                manager.getDiscardNotifier(DEFAULT_DOMAIN),
                errorReporter);
        listener.startListener();
        return listener;
    }
}
