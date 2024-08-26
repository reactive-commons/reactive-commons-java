package org.reactivecommons.async.kafka.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.reactivecommons.async.kafka.listeners.ApplicationEventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicReference;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Configuration
@RequiredArgsConstructor
public class RCKafkaEventListenerConfig {

    @Bean
    public ApplicationEventListener kafkaEventListener(ConnectionManager manager,
                                                       DomainHandlers handlers,
                                                       AsyncKafkaPropsDomain asyncPropsDomain,
                                                       MessageConverter messageConverter,
                                                       CustomReporter customReporter) {
        AtomicReference<ApplicationEventListener> external = new AtomicReference<>();
        manager.forListener((domain, receiver) -> {
            AsyncKafkaProps asyncProps = asyncPropsDomain.getProps(domain);
            if (!asyncProps.getDomain().isIgnoreThisListener()) {
                ApplicationEventListener eventListener = new ApplicationEventListener(receiver,
                        handlers.get(domain),
                        messageConverter,
                        asyncProps.getWithDLQRetry(),
                        asyncProps.getCreateTopology(),
                        asyncProps.getMaxRetries(),
                        asyncProps.getRetryDelay(),
                        manager.getDiscardNotifier(domain),
                        customReporter,
                        asyncProps.getAppName());
                if (DEFAULT_DOMAIN.equals(domain)) {
                    external.set(eventListener);
                }

                eventListener.startListener(manager.getTopologyCreator(domain));
            }
        });

        return external.get();
    }

}
