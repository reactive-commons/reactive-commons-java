package org.reactivecommons.async.rabbit.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.listeners.ApplicationQueryListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Configuration
@RequiredArgsConstructor
@Import(RabbitMqConfig.class)
public class QueryListenerConfig {

    @Value("${spring.application.name}")
    private String appName;

    private final AsyncProps asyncProps;

    @Bean
    public ApplicationQueryListener queryListener(MessageConverter converter,
                                                  DomainHandlers handlers,
                                                  ConnectionManager manager,
                                                  CustomReporter errorReporter) {
        final ApplicationQueryListener listener = new ApplicationQueryListener(manager.getListener(DEFAULT_DOMAIN),
                appName + ".query", handlers.get(DEFAULT_DOMAIN), manager.getSender(DEFAULT_DOMAIN), asyncProps.getDirect().getExchange(), converter,
                asyncProps.getGlobal().getExchange(), asyncProps.getWithDLQRetry(), asyncProps.getMaxRetries(),
                asyncProps.getRetryDelay(), asyncProps.getGlobal().getMaxLengthBytes(),
                asyncProps.getDirect().isDiscardTimeoutQueries(), manager.getDiscardNotifier(DEFAULT_DOMAIN), errorReporter);

        listener.startListener();

        return listener;
    }
}
