package org.reactivecommons.async.commons.utils.resolver;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueueListener;
import org.reactivecommons.async.commons.HandlerResolver;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Stream;

@Log
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@SuppressWarnings({"unchecked", "rawtypes"})
public final class HandlerResolverBuilder {

    public static HandlerResolver buildResolver(String domain, Map<String, HandlerRegistry> registries,
                                                final DefaultCommandHandler defaultCommandHandler) {
        return buildResolver(domain, null, registries, defaultCommandHandler);
    }

    public static HandlerResolver buildResolver(String domain, String aliasDomain,
                                                Map<String, HandlerRegistry> registries,
                                                final DefaultCommandHandler defaultCommandHandler) {
        Collection<HandlerRegistry> allRegistries = registries.values();

        final ConcurrentMap<String, RegisteredQueryHandler<?, ?>> queryHandlers = collectHandlers(
                allRegistries, r -> mergeHandlers(r.getHandlers(), domain, aliasDomain),
                RegisteredQueryHandler::path);

        final ConcurrentMap<String, RegisteredCommandHandler<?, ?>> commandHandlers = collectHandlers(
                allRegistries, r -> mergeHandlers(r.getCommandHandlers(), domain, aliasDomain),
                RegisteredCommandHandler::path);

        final ConcurrentMap<String, RegisteredQueueListener> queueListeners = collectHandlers(
                allRegistries, r -> mergeHandlers(r.getQueueHandlers(), domain, aliasDomain),
                RegisteredQueueListener::queueName);

        final ConcurrentMap<String, RegisteredEventListener<?, ?>> eventNotificationListeners = collectHandlers(
                allRegistries, r -> mergeHandlers(r.getEventNotificationListener(), domain, aliasDomain),
                RegisteredEventListener::path);

        final ConcurrentMap<String, RegisteredEventListener<?, ?>> eventsToBind = collectHandlers(
                allRegistries, r -> mergeHandlers(r.getDomainEventListeners(), domain, aliasDomain),
                RegisteredEventListener::path);

        final ConcurrentMap<String, RegisteredEventListener<?, ?>> eventListeners = collectHandlers(
                allRegistries,
                r -> Stream.concat(
                        mergeHandlers(r.getDomainEventListeners(), domain, aliasDomain),
                        mergeHandlers(r.getDynamicEventHandlers(), domain, aliasDomain)),
                RegisteredEventListener::path);

        return new HandlerResolver(queryHandlers, eventListeners, eventsToBind, eventNotificationListeners,
                commandHandlers, queueListeners) {
            @Override
            public <T, D> RegisteredCommandHandler<T, D> getCommandHandler(String path) {
                final RegisteredCommandHandler<T, D> handler = super.getCommandHandler(path);
                return handler != null ?
                        handler : new RegisteredCommandHandler<>("", defaultCommandHandler, Object.class);
            }
        };
    }

    private static <T> ConcurrentMap<String, T> collectHandlers(Collection<HandlerRegistry> registries,
                                                                Function<HandlerRegistry, Stream<T>> extractor,
                                                                Function<T, String> keyMapper) {
        return registries.stream()
                .flatMap(extractor)
                .collect(ConcurrentHashMap::new, (map, handler)
                        -> map.put(keyMapper.apply(handler), handler), ConcurrentHashMap::putAll);
    }

    private static <T> Stream<T> mergeHandlers(Map<String, ? extends List<T>> handlersMap,
                                               String domain, String aliasDomain) {
        Stream<T> primary = Stream.ofNullable(handlersMap.get(domain)).flatMap(List::stream);
        if (aliasDomain != null && !aliasDomain.equals(domain)) {
            Stream<T> alias = Stream.ofNullable(handlersMap.get(aliasDomain)).flatMap(List::stream);
            return Stream.concat(primary, alias);
        }
        return primary;
    }
}
