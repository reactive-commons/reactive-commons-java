package org.reactivecommons.async.rabbit.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.listeners.ApplicationCommandListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Configuration
@RequiredArgsConstructor
@Import(RabbitMqConfig.class)
public class CommandListenersConfig {

    @Value("${spring.application.name}")
    private String appName;

    private final AsyncProps asyncProps;

    @Bean
    public ApplicationCommandListener applicationCommandListener(ConnectionManager manager,
                                                                 DomainHandlers handlers,
                                                                 MessageConverter converter,
                                                                 CustomReporter errorReporter) {
        ApplicationCommandListener commandListener = new ApplicationCommandListener(manager.getListener(DEFAULT_DOMAIN), appName, handlers.get(DEFAULT_DOMAIN),
                asyncProps.getDirect().getExchange(), converter, asyncProps.getWithDLQRetry(), asyncProps.getMaxRetries(),
                asyncProps.getRetryDelay(), asyncProps.getDirect().getMaxLengthBytes(), manager.getDiscardNotifier(DEFAULT_DOMAIN), errorReporter);

        commandListener.startListener();

        return commandListener;
    }
}
