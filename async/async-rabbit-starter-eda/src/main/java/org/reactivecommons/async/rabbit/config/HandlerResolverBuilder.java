package org.reactivecommons.async.rabbit.config;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.rabbit.HandlerResolver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HandlerResolverBuilder {

    public static HandlerResolver buildResolver(String domain,
                                                Map<String, HandlerRegistry> registries,
                                                final DefaultCommandHandler defaultCommandHandler) {

        if ("app".equals(domain)) {
            final ConcurrentMap<String, RegisteredQueryHandler<?, ?>> queryHandlers = registries
                    .values().stream()
                    .flatMap(r -> r.getHandlers().stream())
                    .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                            ConcurrentHashMap::putAll);

            final ConcurrentMap<String, RegisteredCommandHandler<?>> commandHandlers = registries
                    .values().stream()
                    .flatMap(r -> r.getCommandHandlers().stream())
                    .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                            ConcurrentHashMap::putAll);

            final ConcurrentMap<String, RegisteredEventListener<?>> eventNotificationListener = registries
                    .values()
                    .stream()
                    .flatMap(r -> r.getEventNotificationListener().stream())
                    .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                            ConcurrentHashMap::putAll);

            final ConcurrentMap<String, RegisteredEventListener<?>> eventsToBind = getEventsToBind(domain, registries);

            final ConcurrentMap<String, RegisteredEventListener<?>> eventHandlers = getEventHandlersWithDynamics(domain, registries);

            return new HandlerResolver(queryHandlers, eventHandlers, eventsToBind, eventNotificationListener, commandHandlers) {
                @Override
                @SuppressWarnings("unchecked")
                public <T> RegisteredCommandHandler<T> getCommandHandler(String path) {
                    final RegisteredCommandHandler<T> handler = super.getCommandHandler(path);
                    return handler != null ? handler : new RegisteredCommandHandler<>("", defaultCommandHandler, Object.class);
                }
            };
        }


        final ConcurrentMap<String, RegisteredEventListener<?>> eventsToBind = getEventsToBind(domain, registries);
        final ConcurrentMap<String, RegisteredEventListener<?>> eventHandlers = getEventHandlersWithDynamics(domain, registries);

        return new HandlerResolver(new ConcurrentHashMap<>(), eventHandlers, eventsToBind, new ConcurrentHashMap<>(), new ConcurrentHashMap<>()) {
            @Override
            @SuppressWarnings("unchecked")
            public <T> RegisteredCommandHandler<T> getCommandHandler(String path) {
                final RegisteredCommandHandler<T> handler = super.getCommandHandler(path);
                return handler != null ? handler : new RegisteredCommandHandler<>("", defaultCommandHandler, Object.class);
            }
        };
    }

    private static ConcurrentMap<String, RegisteredEventListener<?>> getEventHandlersWithDynamics(String domain, Map<String, HandlerRegistry> registries) {
        // event handlers and dynamic handlers
        return registries
                .values().stream()
                .flatMap(r -> Stream.concat(r.getDomainEventListeners().get(domain).stream(), getDynamics(domain, r)))
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);
    }

    private static Stream<RegisteredEventListener<?>> getDynamics(String domain, HandlerRegistry r) {
        if ("app".equals(domain)) {
            return r.getDynamicEventHandlers().stream();
        }
        return Stream.of();
    }

    private static ConcurrentMap<String, RegisteredEventListener<?>> getEventsToBind(String domain, Map<String, HandlerRegistry> registries) {
        return registries
                .values().stream()
                .flatMap(r -> r.getDomainEventListeners().get(domain).stream())
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);
    }
}
