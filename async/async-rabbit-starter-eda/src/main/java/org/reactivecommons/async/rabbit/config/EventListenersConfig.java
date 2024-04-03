package org.reactivecommons.async.rabbit.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.rabbit.listeners.ApplicationEventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.atomic.AtomicReference;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Configuration
@RequiredArgsConstructor
@Import(RabbitMqConfig.class)
public class EventListenersConfig {

    private final AsyncPropsDomain asyncPropsDomain;

    @Bean
    public ApplicationEventListener eventListener(MessageConverter messageConverter,
                                                  ConnectionManager manager, DomainHandlers handlers,
                                                  CustomReporter errorReporter) {
        AtomicReference<ApplicationEventListener> external = new AtomicReference<>();
        manager.forListener((domain, receiver) -> {
            AsyncProps asyncProps = asyncPropsDomain.getProps(domain);
            final ApplicationEventListener listener = new ApplicationEventListener(receiver,
                    asyncProps.getBrokerConfigProps().getEventsQueue(),
                    asyncProps.getBrokerConfigProps().getDomainEventsExchangeName(),
                    handlers.get(domain),
                    messageConverter, asyncProps.getWithDLQRetry(),
                    asyncProps.getCreateTopology(),
                    asyncProps.getMaxRetries(),
                    asyncProps.getRetryDelay(),
                    asyncProps.getDomain().getEvents().getMaxLengthBytes(),
                    manager.getDiscardNotifier(domain),
                    errorReporter,
                    asyncProps.getAppName());
            if (DEFAULT_DOMAIN.equals(domain)) {
                external.set(listener);
            }
            listener.startListener();
        });

        return external.get();
    }
}
