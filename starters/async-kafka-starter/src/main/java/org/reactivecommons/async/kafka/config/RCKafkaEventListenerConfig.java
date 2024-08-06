package org.reactivecommons.async.kafka.config;

import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.utils.resolver.HandlerResolverUtil;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.listeners.ApplicationEventListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

@Configuration
public class RCKafkaEventListenerConfig {

    @Bean
    public ApplicationEventListener applicationEventListener(ReactiveMessageListener listener,
                                                             HandlerResolver resolver,
                                                             MessageConverter messageConverter,
                                                             TopologyCreator creator,
                                                             @Value("${spring.application.name}") String appName) {
        ApplicationEventListener eventListener = new ApplicationEventListener(listener,
                resolver,
                messageConverter,
                false,
                true,
                10,
                1000,
                Optional.empty(),
                new DiscardNotifier() {
                    @Override
                    public Mono<Void> notifyDiscard(Message message) {
                        return Mono.empty();
                    }
                },
                new CustomReporter() {
                    @Override
                    public Mono<Void> reportError(Throwable ex, Message rawMessage, Command<?> message, boolean redelivered) {
                        return Mono.empty();
                    }

                    @Override
                    public Mono<Void> reportError(Throwable ex, Message rawMessage, DomainEvent<?> message, boolean redelivered) {
                        return Mono.empty();
                    }

                    @Override
                    public Mono<Void> reportError(Throwable ex, Message rawMessage, AsyncQuery<?> message, boolean redelivered) {
                        return Mono.empty();
                    }
                },
                appName);

        eventListener.startListener(creator);

        return eventListener;
    }

    @Bean
    public HandlerResolver resolver(ApplicationContext context, DefaultCommandHandler<?> defaultCommandHandler) {
        final Map<String, HandlerRegistry> registries = context.getBeansOfType(HandlerRegistry.class);
        return HandlerResolverUtil.fromHandlerRegistries(registries.values(), defaultCommandHandler);
    }

    @Bean
    public DefaultCommandHandler<?> defaultCommandHandler() {
        return command -> Mono.empty();
    }
}
