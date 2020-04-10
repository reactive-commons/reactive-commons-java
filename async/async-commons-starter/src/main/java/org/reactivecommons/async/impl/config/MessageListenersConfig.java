package org.reactivecommons.async.impl.config;

import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.DefaultQueryHandler;
import org.reactivecommons.async.api.DynamicRegistry;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.impl.DynamicRegistryImp;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import org.reactivecommons.async.impl.config.props.BrokerConfigProps;
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
import java.util.concurrent.ConcurrentMap;

@Configuration
@Import(RabbitMqConfig.class)
public class MessageListenersConfig {

    @Value("${app.async.domain.events.exchange:domainEvents}")
    private String domainEventsExchangeName;

    @Value("${spring.application.name}")
    private String appName;

    @Value("${app.async.direct.exchange:directMessages}")
    private String directMessagesExchangeName;

    @Value("${app.async.maxRetries:10}")
    private long maxRetries;

    @Value("${app.async.retryDelay:1000}")
    private int retryDelay;

    @Value("${app.async.withDLQRetry:false}")
    private boolean withDLQRetry;

    @Bean //TODO: move to own config (QueryListenerConfig)
    public ApplicationEventListener eventListener(HandlerResolver resolver, MessageConverter messageConverter, ReactiveMessageListener receiver) throws Exception {
        final ApplicationEventListener listener = new ApplicationEventListener(receiver, appName + ".subsEvents", resolver, domainEventsExchangeName, messageConverter, withDLQRetry, maxRetries, retryDelay);
        listener.startListener();
        return listener;
    }

    @Bean //TODO: move to own config (QueryListenerConfig)
    public ApplicationQueryListener queryListener(MessageConverter converter, HandlerResolver resolver, ReactiveMessageSender sender, ReactiveMessageListener rlistener) throws Exception {
        final ApplicationQueryListener listener = new ApplicationQueryListener(rlistener, appName+".query", resolver, sender, directMessagesExchangeName, converter, "globalReply", withDLQRetry, maxRetries, retryDelay);
        listener.startListener();
        return listener;
    }

    @Bean
    public ApplicationCommandListener applicationCommandListener(ReactiveMessageListener listener, HandlerResolver resolver, MessageConverter converter){
        ApplicationCommandListener commandListener = new ApplicationCommandListener(listener, appName, resolver, directMessagesExchangeName, converter, withDLQRetry, maxRetries, retryDelay);
        commandListener.startListener();
        return commandListener;
    }

    @Bean
    public DynamicRegistry dynamicRegistry(HandlerResolver resolver, ReactiveMessageListener listener, IBrokerConfigProps props) {
        return new DynamicRegistryImp(resolver, listener.getTopologyCreator(), props);
    }

    @Bean
    public HandlerResolver resolver(ApplicationContext context, DefaultQueryHandler defaultHandler, Environment env, DefaultCommandHandler defaultCommandHandler) {
        final Map<String, HandlerRegistry> registries = context.getBeansOfType(HandlerRegistry.class);

        final ConcurrentMap<String, RegisteredQueryHandler> handlers = registries
            .values().stream()
            .flatMap(r -> r.getHandlers().stream())
            .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                ConcurrentHashMap::putAll);

        final ConcurrentMap<String, RegisteredEventListener> eventListeners = registries
            .values().stream()
            .flatMap(r -> r.getEventListeners().stream())
            .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                ConcurrentHashMap::putAll);

        final ConcurrentMap<String, RegisteredCommandHandler> commandHandlers = registries
            .values().stream()
            .flatMap(r -> r.getCommandHandlers().stream())
            .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                ConcurrentHashMap::putAll);

        return new HandlerResolver(handlers, eventListeners, commandHandlers) {
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
