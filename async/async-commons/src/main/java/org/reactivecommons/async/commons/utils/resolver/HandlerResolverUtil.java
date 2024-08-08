package org.reactivecommons.async.commons.utils.resolver;

import lombok.experimental.UtilityClass;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.CommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.commons.HandlerResolver;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@UtilityClass
public class HandlerResolverUtil {

    public static HandlerResolver fromHandlerRegistries(Collection<HandlerRegistry> registries,
                                                        CommandHandler defaultHandler) {
        final ConcurrentMap<String, RegisteredQueryHandler<?, ?>> queryHandlers = registries.stream()
                .flatMap(r -> r.getHandlers().stream())
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);

        final ConcurrentMap<String, RegisteredEventListener<?, ?>> eventsToBind = registries.stream()
                .flatMap(r -> r.getDomainEventListeners().get(DEFAULT_DOMAIN).stream())
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);

        // event handlers and dynamic handlers
        final ConcurrentMap<String, RegisteredEventListener<?, ?>> eventHandlers = registries.stream()
                .flatMap(r -> Stream.concat(r.getDomainEventListeners().get(DEFAULT_DOMAIN).stream(),
                        r.getDynamicEventHandlers().stream()))
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);

        final ConcurrentMap<String, RegisteredCommandHandler<?, ?>> commandHandlers = registries.stream()
                .flatMap(r -> r.getCommandHandlers().stream())
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);

        final ConcurrentMap<String, RegisteredEventListener<?, ?>> eventNotificationListener = registries.stream()
                .flatMap(r -> r.getEventNotificationListener().stream())
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);

        return new HandlerResolver(queryHandlers, eventHandlers, eventsToBind, eventNotificationListener,
                commandHandlers) {
            @Override
            @SuppressWarnings("unchecked")
            public <T, D> RegisteredCommandHandler<T, D> getCommandHandler(String path) {
                final RegisteredCommandHandler<T, D> handler = super.getCommandHandler(path);
                return handler != null ? handler : new RegisteredCommandHandler<>("", defaultHandler, Object.class);
            }
        };
    }
}
