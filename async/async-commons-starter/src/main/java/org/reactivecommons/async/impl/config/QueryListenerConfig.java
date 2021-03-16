package org.reactivecommons.async.impl.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.impl.DiscardNotifier;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.config.props.AsyncProps;
import org.reactivecommons.async.parent.converters.MessageConverter;
import org.reactivecommons.async.parent.ext.CustomErrorReporter;
import org.reactivecommons.async.impl.listeners.ApplicationQueryListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@RequiredArgsConstructor
@Import(RabbitMqConfig.class)
public class QueryListenerConfig {

    @Value("${spring.application.name}")
    private String appName;

    private final AsyncProps asyncProps;

    @Bean
    public ApplicationQueryListener queryListener(MessageConverter converter, HandlerResolver resolver,
                                                  ReactiveMessageSender sender, ReactiveMessageListener rlistener,
                                                  DiscardNotifier discardNotifier,
                                                  CustomErrorReporter errorReporter) {
        final ApplicationQueryListener listener = new ApplicationQueryListener(rlistener,
                appName + ".query", resolver, sender, asyncProps.getDirect().getExchange(), converter,
                asyncProps.getGlobal().getExchange(), asyncProps.getWithDLQRetry(), asyncProps.getMaxRetries(),
                asyncProps.getRetryDelay(),asyncProps.getGlobal().getMaxLengthBytes(),  discardNotifier, errorReporter);

        listener.startListener();

        return listener;
    }
}
