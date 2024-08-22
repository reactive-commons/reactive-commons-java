package org.reactivecommons.async.rabbit.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.rabbit.listeners.ApplicationNotificationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Configuration
@RequiredArgsConstructor
@Import(RabbitMqConfig.class)
public class NotificationListenersConfig {

    private final AsyncPropsDomain asyncPropsDomain;

    @Bean
    public ApplicationNotificationListener eventNotificationListener(ConnectionManager manager,
                                                                     DomainHandlers handlers,
                                                                     MessageConverter messageConverter,
                                                                     CustomReporter errorReporter) {
        AsyncProps asyncProps = asyncPropsDomain.getProps(DEFAULT_DOMAIN);
        final ApplicationNotificationListener listener = new ApplicationNotificationListener(
                manager.getListener(DEFAULT_DOMAIN),
                asyncProps.getBrokerConfigProps().getDomainEventsExchangeName(),
                asyncProps.getBrokerConfigProps().getNotificationsQueue(),
                asyncProps.getCreateTopology(),
                handlers.get(DEFAULT_DOMAIN),
                messageConverter,
                manager.getDiscardNotifier(DEFAULT_DOMAIN),
                errorReporter);
        listener.startListener();
        return listener;
    }
}
