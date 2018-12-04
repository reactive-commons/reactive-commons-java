package org.reactivecommons.async.impl.config;

import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.DefaultQueryHandler;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.impl.listeners.ApplicationCommandListener;
import org.reactivecommons.async.impl.listeners.ApplicationEventListener;
import org.reactivecommons.async.impl.listeners.ApplicationQueryListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@Import(RabbitMqConfig.class)
public class MessageListenersConfig {

    @Value("${app.async.domain.events.exchange:domainEvents}")
    private String domainEventsExchangeName;

    @Value("${spring.application.name}")
    private String appName;

    @Value("${app.async.direct.exchange:directMessages}")
    private String directMessagesExchangeName;

    @Bean //TODO: move to own config (QueryListenerConfig)
    public ApplicationEventListener eventListener(HandlerResolver resolver, MessageConverter messageConverter, ReactiveMessageListener receiver) throws Exception {
        final ApplicationEventListener listener = new ApplicationEventListener(receiver, appName + ".subsEvents", resolver, domainEventsExchangeName, messageConverter);
        listener.startListener();
        return listener;
    }

    @Bean //TODO: move to own config (QueryListenerConfig)
    public ApplicationQueryListener queryListener(MessageConverter converter, HandlerResolver resolver, ReactiveMessageSender sender, ReactiveMessageListener rlistener) throws Exception {
        final ApplicationQueryListener listener = new ApplicationQueryListener(rlistener, appName+".query", resolver, sender, directMessagesExchangeName, converter, "globalReply");
        listener.startListener();
        return listener;
    }

    @Bean
    public ApplicationCommandListener applicationCommandListener(ReactiveMessageListener listener, HandlerResolver resolver, MessageConverter converter){
        ApplicationCommandListener commandListener = new ApplicationCommandListener(listener, appName, resolver, directMessagesExchangeName, converter);
        commandListener.startListener();
        return commandListener;
    }

    @Bean
    public HandlerResolver resolver(ApplicationContext context, DefaultQueryHandler defaultHandler, Environment env, DefaultCommandHandler defaultCommandHandler) {
        final Map<String, HandlerRegistry> registries = context.getBeansOfType(HandlerRegistry.class);

        final ConcurrentHashMap<String, QueryHandler<?, ?>> handlers = registries
            .values().stream()
            .flatMap(r -> r.getHandlers().stream())
            .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler.getHandler()),
                ConcurrentHashMap::putAll);

        final Map<String, RegisteredEventListener> eventListeners = registries
            .values().stream()
            .flatMap(r -> r.getEventListeners().stream())
            .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                ConcurrentHashMap::putAll);

        final Map<String, RegisteredCommandHandler> commandHandlers = registries
            .values().stream()
            .flatMap(r -> r.getCommandHandlers().stream())
            .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                ConcurrentHashMap::putAll);

        return new HandlerResolver(handlers, eventListeners, commandHandlers) {
            @Override
            @SuppressWarnings("unchecked")
            public QueryHandler<?, ?> getQueryHandler(String path) {
                final QueryHandler<?, ?> handler = super.getQueryHandler(path);
                if (handler == null) {
                    try {
                        final String handlerName = env.getProperty(path);
                        return context.getBean(handlerName, QueryHandler.class);
                    } catch (Exception e) {
                        return defaultHandler;
                    }
                }
                return handler;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> RegisteredCommandHandler<T> getCommandHandler(String path) {
                final RegisteredCommandHandler<T> handler = super.getCommandHandler(path);
                return handler != null ? handler : new RegisteredCommandHandler<>("", defaultCommandHandler, Object.class);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultQueryHandler defaultHandler() {
        return (DefaultQueryHandler<Object, Object>) command ->
            Mono.error(new RuntimeException("No Handler Registered"));
    }


    @Bean
    @ConditionalOnMissingBean
    public DefaultCommandHandler defaultCommandHandler(){
        return message -> Mono.error(new RuntimeException("No Handler Registered"));
    }
}
