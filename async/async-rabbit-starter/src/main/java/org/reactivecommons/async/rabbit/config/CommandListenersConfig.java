package org.reactivecommons.async.rabbit.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.listeners.ApplicationCommandListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@RequiredArgsConstructor
@Import(RabbitMqConfig.class)
public class CommandListenersConfig {

    @Value("${spring.application.name}")
    private String appName;

    private final AsyncProps asyncProps;

    @Bean
    public ApplicationCommandListener applicationCommandListener(ReactiveMessageListener listener,
                                                                 HandlerResolver resolver, MessageConverter converter,
                                                                 DiscardNotifier discardNotifier,
                                                                 IBrokerConfigProps brokerConfigProps,
                                                                 CustomReporter errorReporter) {
        ApplicationCommandListener commandListener = new ApplicationCommandListener(listener,
                brokerConfigProps.getCommandsQueue(), resolver,
                brokerConfigProps.getDirectMessagesExchangeName(), converter, asyncProps.getWithDLQRetry(),
                asyncProps.getCreateTopology(), asyncProps.getDelayedCommands(), asyncProps.getMaxRetries(),
                asyncProps.getRetryDelay(), asyncProps.getDirect().getMaxLengthBytes(), discardNotifier, errorReporter);

        commandListener.startListener();

        return commandListener;
    }
}
