package org.reactivecommons.async.api;

import io.cloudevents.CloudEvent;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.reactivecommons.api.domain.RawMessage;
import org.reactivecommons.async.api.handlers.CloudCommandHandler;
import org.reactivecommons.async.api.handlers.CloudEventHandler;
import org.reactivecommons.async.api.handlers.DomainCommandHandler;
import org.reactivecommons.async.api.handlers.DomainEventHandler;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.api.handlers.QueryHandlerDelegate;
import org.reactivecommons.async.api.handlers.RawEventHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Getter
@NoArgsConstructor(access = AccessLevel.PACKAGE)
public final class HandlerRegistry {
    public static final String DEFAULT_DOMAIN = "app";
    private final Map<String, List<RegisteredEventListener<?, ?>>> domainEventListeners = new ConcurrentHashMap<>();
    private final List<RegisteredEventListener<?, ?>> dynamicEventHandlers = new CopyOnWriteArrayList<>();
    private final List<RegisteredEventListener<?, ?>> eventNotificationListener = new CopyOnWriteArrayList<>();
    private final List<RegisteredQueryHandler<?, ?>> handlers = new CopyOnWriteArrayList<>();
    private final List<RegisteredCommandHandler<?, ?>> commandHandlers = new CopyOnWriteArrayList<>();


    public static HandlerRegistry register() {
        HandlerRegistry instance = new HandlerRegistry();
        instance.domainEventListeners.put(DEFAULT_DOMAIN, new CopyOnWriteArrayList<>());
        return instance;
    }

    public <T> HandlerRegistry listenDomainEvent(String domain, String eventName, DomainEventHandler<T> handler,
                                                 Class<T> eventClass) {
        domainEventListeners.computeIfAbsent(domain, ignored -> new CopyOnWriteArrayList<>())
                .add(new RegisteredEventListener<>(eventName, handler, eventClass));
        return this;
    }

    public HandlerRegistry listenDomainCloudEvent(String domain, String eventName, CloudEventHandler handler) {
        domainEventListeners.computeIfAbsent(domain, ignored -> new CopyOnWriteArrayList<>())
                .add(new RegisteredEventListener<>(eventName, handler, CloudEvent.class));
        return this;
    }

    public HandlerRegistry listenDomainRawEvent(String domain, String eventName, RawEventHandler<?> handler) {
        domainEventListeners.computeIfAbsent(domain, ignored -> new CopyOnWriteArrayList<>())
                .add(new RegisteredEventListener<>(eventName, handler, RawMessage.class));
        return this;
    }

    public <T> HandlerRegistry listenEvent(String eventName, DomainEventHandler<T> handler, Class<T> eventClass) {
        domainEventListeners.computeIfAbsent(DEFAULT_DOMAIN, ignored -> new CopyOnWriteArrayList<>())
                .add(new RegisteredEventListener<>(eventName, handler, eventClass));
        return this;
    }

    public HandlerRegistry listenCloudEvent(String eventName, CloudEventHandler handler) {
        domainEventListeners.computeIfAbsent(DEFAULT_DOMAIN, ignored -> new CopyOnWriteArrayList<>())
                .add(new RegisteredEventListener<>(eventName, handler, CloudEvent.class));
        return this;
    }

    public <T> HandlerRegistry listenNotificationEvent(String eventName, DomainEventHandler<T> handler,
                                                       Class<T> eventClass) {
        eventNotificationListener.add(new RegisteredEventListener<>(eventName, handler, eventClass));
        return this;
    }

    public HandlerRegistry listenNotificationCloudEvent(String eventName, CloudEventHandler handler) {
        eventNotificationListener.add(new RegisteredEventListener<>(eventName, handler, CloudEvent.class));
        return this;
    }

    public <T> HandlerRegistry handleDynamicEvents(String eventNamePattern, DomainEventHandler<T> handler,
                                                   Class<T> eventClass) {
        dynamicEventHandlers.add(new RegisteredEventListener<>(eventNamePattern, handler, eventClass));
        return this;
    }

    public HandlerRegistry handleDynamicCloudEvents(String eventNamePattern, CloudEventHandler handler) {
        dynamicEventHandlers.add(new RegisteredEventListener<>(eventNamePattern, handler, CloudEvent.class));
        return this;
    }

    public <T> HandlerRegistry handleCommand(String commandName, DomainCommandHandler<T> fn, Class<T> commandClass) {
        commandHandlers.add(new RegisteredCommandHandler<>(commandName, fn, commandClass));
        return this;
    }

    public HandlerRegistry handleCloudEventCommand(String commandName, CloudCommandHandler handler) {
        commandHandlers.add(new RegisteredCommandHandler<>(commandName, handler, CloudEvent.class));
        return this;
    }

    public <T, R> HandlerRegistry serveQuery(String resource, QueryHandler<T, R> handler, Class<R> queryClass) {
        handlers.add(new RegisteredQueryHandler<>(resource, (ignored, message) ->
                handler.handle(message), queryClass
        ));
        return this;
    }

    public <R> HandlerRegistry serveQuery(String resource, QueryHandlerDelegate<Void, R> handler, Class<R> queryClass) {
        handlers.add(new RegisteredQueryHandler<>(resource, handler, queryClass));
        return this;
    }

    public <R> HandlerRegistry serveCloudEventQuery(String resource, QueryHandler<R, CloudEvent> handler) {
        handlers.add(new RegisteredQueryHandler<>(resource, (ignored, message) ->
                handler.handle(message), CloudEvent.class
        ));
        return this;
    }

    public <R> HandlerRegistry serveCloudEventQuery(String resource, QueryHandlerDelegate<Void, CloudEvent> handler) {
        handlers.add(new RegisteredQueryHandler<>(resource, handler, CloudEvent.class));
        return this;
    }


    @Deprecated(forRemoval = true)
    public <T> HandlerRegistry listenEvent(String eventName, DomainEventHandler<T> handler) {
        return listenEvent(eventName, handler, inferGenericParameterType(handler));
    }

    @Deprecated(forRemoval = true)
    public <T> HandlerRegistry handleDynamicEvents(String eventNamePattern, DomainEventHandler<T> handler) {
        return handleDynamicEvents(eventNamePattern, handler, inferGenericParameterType(handler));
    }

    @Deprecated(forRemoval = true)
    public <T> HandlerRegistry handleCommand(String commandName, DomainCommandHandler<T> handler) {
        commandHandlers.add(new RegisteredCommandHandler<>(commandName, handler, inferGenericParameterType(handler)));
        return this;
    }

    @Deprecated(forRemoval = true)
    public <T, R> HandlerRegistry serveQuery(String resource, QueryHandler<T, R> handler) {
        return serveQuery(resource, handler, inferGenericParameterType(handler));
    }

    @Deprecated(forRemoval = true)
    @SuppressWarnings("unchecked")
    private <T, R> Class<R> inferGenericParameterType(QueryHandler<T, R> handler) {
        try {
            ParameterizedType genericSuperclass = (ParameterizedType) handler.getClass().getGenericInterfaces()[0];
            return (Class<R>) genericSuperclass.getActualTypeArguments()[1];
        } catch (Exception e) {
            throw new RuntimeException("Fail to infer generic Query class, please use serveQuery(path, handler, " +
                    "class) instead");
        }
    }

    @Deprecated(forRemoval = true)
    @SuppressWarnings("unchecked")
    private <T> Class<T> inferGenericParameterType(DomainCommandHandler<T> handler) {
        try {
            ParameterizedType genericSuperclass = (ParameterizedType) handler.getClass().getGenericInterfaces()[0];
            return (Class<T>) genericSuperclass.getActualTypeArguments()[0];
        } catch (Exception e) {
            throw new RuntimeException("Fail to infer generic Command class, please use handleCommand(path, handler, " +
                    "class) instead");
        }
    }

    @Deprecated(forRemoval = true)
    private <T> Class<T> inferGenericParameterType(DomainEventHandler<T> handler) {
        try {
            ParameterizedType genericSuperclass = (ParameterizedType) handler.getClass().getGenericInterfaces()[0];
            return (Class<T>) genericSuperclass.getActualTypeArguments()[0];
        } catch (Exception e) {
            throw new RuntimeException("Fail to infer generic Query class, please use listenEvent(eventName, handler," +
                    " class) instead");
        }
    }
}



