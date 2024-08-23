package org.reactivecommons.async.rabbit.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.rabbit.listeners.ApplicationCommandListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Configuration
@RequiredArgsConstructor
@Import(RabbitMqConfig.class)
public class CommandListenersConfig {
    private final AsyncPropsDomain asyncPropsDomain;

    @Bean
    public ApplicationCommandListener applicationCommandListener(ConnectionManager manager,
                                                                 DomainHandlers handlers,
                                                                 MessageConverter converter,
                                                                 CustomReporter errorReporter) {
        AsyncProps asyncProps = asyncPropsDomain.getProps(DEFAULT_DOMAIN);
        ApplicationCommandListener commandListener = new ApplicationCommandListener(manager.getListener(DEFAULT_DOMAIN),
                asyncProps.getBrokerConfigProps().getCommandsQueue(), handlers.get(DEFAULT_DOMAIN),
                asyncProps.getDirect().getExchange(), converter, asyncProps.getWithDLQRetry(),
                asyncProps.getCreateTopology(), asyncProps.getDelayedCommands(), asyncProps.getMaxRetries(),
                asyncProps.getRetryDelay(), asyncProps.getDirect().getMaxLengthBytes(),
                manager.getDiscardNotifier(DEFAULT_DOMAIN), errorReporter);

        commandListener.startListener();

        return commandListener;
    }
}
