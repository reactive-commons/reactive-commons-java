package org.reactivecommons.async.rabbit.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.listeners.ApplicationQueryListener;
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
                                                  IBrokerConfigProps brokerConfigProps,
                                                  CustomReporter errorReporter) {
        final ApplicationQueryListener listener = new ApplicationQueryListener(rlistener,
                brokerConfigProps.getQueriesQueue(), resolver, sender, brokerConfigProps.getDirectMessagesExchangeName(), converter,
                brokerConfigProps.getGlobalReplyExchangeName(), asyncProps.getWithDLQRetry(), asyncProps.getCreateTopology(),
                asyncProps.getMaxRetries(), asyncProps.getRetryDelay(), asyncProps.getGlobal().getMaxLengthBytes(),
                asyncProps.getDirect().isDiscardTimeoutQueries(), discardNotifier, errorReporter);

        listener.startListener();

        return listener;
    }
}
