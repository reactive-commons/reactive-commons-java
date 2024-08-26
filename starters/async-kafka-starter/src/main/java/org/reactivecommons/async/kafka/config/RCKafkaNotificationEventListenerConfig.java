package org.reactivecommons.async.kafka.config;

import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.reactivecommons.async.kafka.listeners.ApplicationNotificationsListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Configuration
public class RCKafkaNotificationEventListenerConfig {

    @Bean
    public ApplicationNotificationsListener kafkaNotificationEventListener(ConnectionManager manager,
                                                                           DomainHandlers handlers,
                                                                           AsyncKafkaPropsDomain asyncPropsDomain,
                                                                           MessageConverter messageConverter,
                                                                           CustomReporter customReporter) {
        AsyncKafkaProps props = asyncPropsDomain.getProps(DEFAULT_DOMAIN);
        ApplicationNotificationsListener eventListener = new ApplicationNotificationsListener(
                manager.getListener(DEFAULT_DOMAIN),
                handlers.get(DEFAULT_DOMAIN),
                messageConverter,
                props.getWithDLQRetry(),
                props.getCreateTopology(),
                props.getMaxRetries(),
                props.getRetryDelay(),
                manager.getDiscardNotifier(DEFAULT_DOMAIN),
                customReporter,
                props.getAppName());

        eventListener.startListener(manager.getTopologyCreator(DEFAULT_DOMAIN));

        return eventListener;
    }
}
