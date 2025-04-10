package org.reactivecommons.async.commons.utils.resolver;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.commons.HandlerResolver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

@Log
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HandlerResolverBuilder {

    public static HandlerResolver buildResolver(String domain, Map<String, HandlerRegistry> registries,
                                                final DefaultCommandHandler defaultCommandHandler) {
        final ConcurrentMap<String, RegisteredQueryHandler<?, ?>> queryHandlers = registries
                .values()
                .stream()
                .flatMap(r -> r.getHandlers()
                        .getOrDefault(domain, List.of())
                        .stream())
                .collect(ConcurrentHashMap::new, (map, handler)
                        -> map.put(handler.getPath(), handler), ConcurrentHashMap::putAll
                );

        final ConcurrentMap<String, RegisteredCommandHandler<?, ?>> commandHandlers = registries
                .values()
                .stream()
                .flatMap(r -> r.getCommandHandlers()
                        .getOrDefault(domain, List.of())
                        .stream())
                .collect(ConcurrentHashMap::new, (map, handler)
                        -> map.put(handler.getPath(), handler), ConcurrentHashMap::putAll
                );

        final ConcurrentMap<String, RegisteredEventListener<?, ?>> eventNotificationListener = registries
                .values()
                .stream()
                .flatMap(r -> r.getEventNotificationListener()
                        .getOrDefault(domain, List.of())
                        .stream())
                .collect(ConcurrentHashMap::new, (map, handler)
                        -> map.put(handler.getPath(), handler), ConcurrentHashMap::putAll
                );

        final ConcurrentMap<String, RegisteredEventListener<?, ?>> eventsToBind = getEventsToBind(domain,
                registries);

        final ConcurrentMap<String, RegisteredEventListener<?, ?>> eventHandlers =
                getEventHandlersWithDynamics(domain, registries);

        return new HandlerResolver(queryHandlers, eventHandlers, eventsToBind, eventNotificationListener,
                commandHandlers) {
            @Override
            @SuppressWarnings("unchecked")
            public <T, D> RegisteredCommandHandler<T, D> getCommandHandler(String path) {
                final RegisteredCommandHandler<T, D> handler = super.getCommandHandler(path);
                return handler != null ?
                        handler : new RegisteredCommandHandler<>("", defaultCommandHandler, Object.class);
            }
        };
    }

    private static ConcurrentMap<String, RegisteredEventListener<?, ?>> getEventHandlersWithDynamics(
            String domain, Map<String, HandlerRegistry> registries) {
        // event handlers and dynamic handlers
        return registries
                .values()
                .stream()
                .flatMap(r -> Stream.concat(r.getDomainEventListeners()
                                .getOrDefault(domain, List.of())
                                .stream(),
                        getDynamics(domain, r)))
                .collect(ConcurrentHashMap::new, (map, handler)
                        -> map.put(handler.getPath(), handler), ConcurrentHashMap::putAll
                );
    }

    private static Stream<RegisteredEventListener<?, ?>> getDynamics(String domain, HandlerRegistry r) {
        return r.getDynamicEventHandlers().getOrDefault(domain, List.of()).stream();
    }

    private static ConcurrentMap<String, RegisteredEventListener<?, ?>> getEventsToBind(
            String domain, Map<String, HandlerRegistry> registries) {
        return registries
                .values().stream()
                .flatMap(r -> {
                    if (r.getDomainEventListeners().containsKey(domain)) {
                        return r.getDomainEventListeners().get(domain).stream();
                    }
                    return Stream.empty();
                })
                .collect(ConcurrentHashMap::new, (map, handler)
                        -> map.put(handler.getPath(), handler), ConcurrentHashMap::putAll
                );
    }
}
