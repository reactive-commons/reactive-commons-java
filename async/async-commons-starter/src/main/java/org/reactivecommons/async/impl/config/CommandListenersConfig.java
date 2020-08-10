package org.reactivecommons.async.impl.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.impl.DiscardNotifier;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.config.props.AsyncProps;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.impl.listeners.ApplicationCommandListener;
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
                                                                 DiscardNotifier discardNotifier) {
        ApplicationCommandListener commandListener = new ApplicationCommandListener(listener, appName, resolver,
                asyncProps.getDirect().getExchange(), converter, asyncProps.getWithDLQRetry(), asyncProps.getMaxRetries(),
                asyncProps.getRetryDelay(), asyncProps.getDirect().getMaxLengthBytes(), discardNotifier);

        commandListener.startListener();

        return commandListener;
    }
}
