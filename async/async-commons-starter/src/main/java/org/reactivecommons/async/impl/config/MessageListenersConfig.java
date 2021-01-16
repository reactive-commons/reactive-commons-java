package org.reactivecommons.async.impl.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.DefaultQueryHandler;
import org.reactivecommons.async.api.DynamicRegistry;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.impl.DiscardNotifier;
import org.reactivecommons.async.impl.DynamicRegistryImp;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.config.props.AsyncProps;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.impl.ext.CustomErrorReporter;
import org.reactivecommons.async.impl.listeners.ApplicationCommandListener;
import org.reactivecommons.async.impl.listeners.ApplicationEventListener;
import org.reactivecommons.async.impl.listeners.ApplicationNotificationListener;
import org.reactivecommons.async.impl.listeners.ApplicationQueryListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Configuration
@RequiredArgsConstructor
@Deprecated
@Import(RabbitMqConfig.class)
public class MessageListenersConfig {

    @Value("${spring.application.name}")
    private String appName;

    private final AsyncProps asyncProps;


    @Bean //TODO: move to own config (QueryListenerConfig)
    public ApplicationEventListener eventListener(HandlerResolver resolver, MessageConverter messageConverter,
                                                  ReactiveMessageListener receiver, DiscardNotifier discardNotifier, CustomErrorReporter errorReporter) {
        final ApplicationEventListener listener = new ApplicationEventListener(receiver,
                appName + ".subsEvents", resolver, asyncProps.getDomain().getEvents().getExchange(),
                messageConverter, asyncProps.getWithDLQRetry(), asyncProps.getMaxRetries(), asyncProps.getRetryDelay(),
                asyncProps.getDomain().getEvents().getMaxLengthBytes(), discardNotifier, errorReporter);
        listener.startListener();
        return listener;
    }

    @Bean
    public ApplicationNotificationListener eventNotificationListener(HandlerResolver resolver, MessageConverter messageConverter,
                                                                     ReactiveMessageListener receiver, DiscardNotifier discardNotifier, CustomErrorReporter errorReporter) {
        final ApplicationNotificationListener listener = new ApplicationNotificationListener(
                receiver,
                asyncProps.getDomain().getEvents().getExchange(),
                asyncProps.getNotificationProps().getQueueName(appName),
                resolver,
                messageConverter,
                discardNotifier,
                errorReporter);
        listener.startListener();
        return listener;
    }

    @Bean //TODO: move to own config (QueryListenerConfig)
    public ApplicationQueryListener queryListener(MessageConverter converter, HandlerResolver resolver,
                                                  ReactiveMessageSender sender, ReactiveMessageListener rlistener,
                                                  DiscardNotifier discardNotifier,
                                                  CustomErrorReporter errorReporter) {
        final ApplicationQueryListener listener = new ApplicationQueryListener(rlistener,
                appName + ".query", resolver, sender, asyncProps.getDirect().getExchange(), converter,
                asyncProps.getGlobal().getExchange(), asyncProps.getWithDLQRetry(), asyncProps.getMaxRetries(),
                asyncProps.getRetryDelay(), asyncProps.getGlobal().getMaxLengthBytes(), discardNotifier, errorReporter);
        listener.startListener();
        return listener;
    }

    @Bean
    public ApplicationCommandListener applicationCommandListener(ReactiveMessageListener listener,
                                                                 HandlerResolver resolver, MessageConverter converter,
                                                                 DiscardNotifier discardNotifier,
                                                                 CustomErrorReporter errorReporter) {
        ApplicationCommandListener commandListener = new ApplicationCommandListener(listener, appName, resolver,
                asyncProps.getDirect().getExchange(), converter, asyncProps.getWithDLQRetry(), asyncProps.getMaxRetries(),
                asyncProps.getRetryDelay(), asyncProps.getDirect().getMaxLengthBytes(), discardNotifier, errorReporter);
        commandListener.startListener();
        return commandListener;
    }

    @Bean
    public DynamicRegistry dynamicRegistry(HandlerResolver resolver, ReactiveMessageListener listener, IBrokerConfigProps props) {
        return new DynamicRegistryImp(resolver, listener.getTopologyCreator(), props);
    }

    @Bean
    public HandlerResolver resolver(ApplicationContext context, DefaultCommandHandler defaultCommandHandler) {
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

        final ConcurrentMap<String, RegisteredEventListener> eventNotificationListener = registries
                .values()
                .stream()
                .flatMap(r -> r.getEventNotificationListener().stream())
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);

        return new HandlerResolver(handlers, eventListeners, commandHandlers, eventNotificationListener) {
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
    public DefaultCommandHandler defaultCommandHandler() {
        return message -> Mono.error(new RuntimeException("No Handler Registered"));
    }
}
