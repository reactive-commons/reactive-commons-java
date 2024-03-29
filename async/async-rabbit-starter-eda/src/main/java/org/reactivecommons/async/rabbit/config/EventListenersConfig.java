package org.reactivecommons.async.rabbit.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.listeners.ApplicationEventListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.atomic.AtomicReference;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Configuration
@RequiredArgsConstructor
@Import(RabbitMqConfig.class)
public class EventListenersConfig {

    @Value("${spring.application.name}")
    private String appName;

    private final AsyncProps asyncProps;

    @Bean
    public ApplicationEventListener eventListener(MessageConverter messageConverter,
                                                  ConnectionManager manager, DomainHandlers handlers,
                                                  CustomReporter errorReporter) {
        AtomicReference<ApplicationEventListener> external = new AtomicReference<>();
        manager.forListener((domain, receiver) -> {
            final ApplicationEventListener listener = new ApplicationEventListener(receiver,
                    appName + ".subsEvents",
                    handlers.get(domain),
                    asyncProps.getDomain().getEvents().getExchange(),
                    messageConverter, asyncProps.getWithDLQRetry(),
                    asyncProps.getMaxRetries(),
                    asyncProps.getRetryDelay(),
                    asyncProps.getDomain().getEvents().getMaxLengthBytes(),
                    manager.getDiscardNotifier(domain),
                    errorReporter,
                    appName);
            if (DEFAULT_DOMAIN.equals(domain)) {
                external.set(listener);
            }
            listener.startListener();
        });

        return external.get();
    }
}
