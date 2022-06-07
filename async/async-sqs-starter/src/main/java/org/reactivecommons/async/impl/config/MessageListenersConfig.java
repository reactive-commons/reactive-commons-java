package org.reactivecommons.async.impl.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.DefaultQueryHandler;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.impl.Handlers;
import org.reactivecommons.async.impl.config.props.AsyncProps;
import org.reactivecommons.async.impl.converters.JacksonMessageConverter;
import org.reactivecommons.async.impl.handlers.ApplicationCommandHandler;
import org.reactivecommons.async.impl.handlers.ApplicationEventHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Configuration
@RequiredArgsConstructor
public class MessageListenersConfig {

    @Value("${spring.application.name}")
    private String appName;

    private final AsyncProps asyncProps;


    @Bean //TODO: move to own config (QueryListenerConfig)
    public ApplicationEventHandler eventListener(HandlerResolver resolver, MessageConverter messageConverter) {
        return new ApplicationEventHandler(resolver, messageConverter);
    }

    @Bean
    public ApplicationCommandHandler applicationCommandListener(HandlerResolver resolver, MessageConverter messageConverter) {
        return new ApplicationCommandHandler(resolver, messageConverter);
    }

    @Bean
    public HandlerResolver resolver(ApplicationContext context, DefaultCommandHandler<?> defaultCommandHandler) {
        final Map<String, HandlerRegistry> registries = context.getBeansOfType(HandlerRegistry.class);

        final ConcurrentMap<String, RegisteredQueryHandler<?,?>> handlers = registries
                .values().stream()
                .flatMap(r -> r.getHandlers().stream())
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);

        final ConcurrentMap<String, RegisteredEventListener<?>> eventListeners = registries
                .values().stream()
                .flatMap(r -> r.getEventListeners().stream())
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);

        final ConcurrentMap<String, RegisteredEventListener<?>> eventsToBind = registries
                .values().stream()
                .flatMap(r -> r.getEventListeners().stream())
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);

        final ConcurrentMap<String, RegisteredCommandHandler<?>> commandHandlers = registries
                .values().stream()
                .flatMap(r -> r.getCommandHandlers().stream())
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);

        final ConcurrentMap<String, RegisteredEventListener<?>> notificationHandlers = registries
                .values().stream()
                .flatMap(r -> r.getEventListeners().stream())
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);


        return new HandlerResolver(handlers, eventListeners, eventsToBind, notificationHandlers, commandHandlers);

    }

    @Bean
    @ConditionalOnMissingBean
    public MessageConverter messageConverter() {
        ObjectMapper mapper = new ObjectMapper();
        return new JacksonMessageConverter(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultQueryHandler defaultHandler() {
        return (DefaultQueryHandler<Object, Object>) command ->
                Mono.error(new RuntimeException("No Handler Registered"));
    }


    @Bean
    @ConditionalOnMissingBean
    public DefaultCommandHandler defaultCommandHandler() {
        return message -> Mono.error(new RuntimeException("No Handler Registered"));
    }
}
