package org.reactivecommons.async.rabbit.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.rabbit.listeners.ApplicationQueryListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Configuration
@RequiredArgsConstructor
@Import(RabbitMqConfig.class)
public class QueryListenerConfig {

    private final AsyncPropsDomain asyncPropsDomain;

    @Bean
    public ApplicationQueryListener queryListener(MessageConverter converter,
                                                  DomainHandlers handlers,
                                                  ConnectionManager manager,
                                                  CustomReporter errorReporter) {
        AsyncProps asyncProps = asyncPropsDomain.getProps(DEFAULT_DOMAIN);
        final ApplicationQueryListener listener = new ApplicationQueryListener(manager.getListener(DEFAULT_DOMAIN),
                asyncProps.getBrokerConfigProps().getQueriesQueue(), handlers.get(DEFAULT_DOMAIN),
                manager.getSender(DEFAULT_DOMAIN), asyncProps.getBrokerConfigProps().getDirectMessagesExchangeName(),
                converter, asyncProps.getBrokerConfigProps().getGlobalReplyExchangeName(), asyncProps.getWithDLQRetry(),
                asyncProps.getCreateTopology(), asyncProps.getMaxRetries(),
                asyncProps.getRetryDelay(), asyncProps.getGlobal().getMaxLengthBytes(),
                asyncProps.getDirect().isDiscardTimeoutQueries(),
                manager.getDiscardNotifier(DEFAULT_DOMAIN), errorReporter);

        listener.startListener();

        return listener;
    }
}
