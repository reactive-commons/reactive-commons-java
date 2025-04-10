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
import org.reactivecommons.async.api.handlers.RawCommandHandler;
import org.reactivecommons.async.api.handlers.RawEventHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredDomainHandlers;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;

import java.lang.reflect.ParameterizedType;
import java.util.concurrent.CopyOnWriteArrayList;

@Getter
@NoArgsConstructor(access = AccessLevel.PACKAGE)
public final class HandlerRegistry {
    public static final String DEFAULT_DOMAIN = "app";
    private final RegisteredDomainHandlers<RegisteredEventListener<?, ?>> domainEventListeners =
            new RegisteredDomainHandlers<>();
    private final RegisteredDomainHandlers<RegisteredEventListener<?, ?>> dynamicEventHandlers =
            new RegisteredDomainHandlers<>();
    private final RegisteredDomainHandlers<RegisteredEventListener<?, ?>> eventNotificationListener =
            new RegisteredDomainHandlers<>();
    private final RegisteredDomainHandlers<RegisteredQueryHandler<?, ?>> handlers = new RegisteredDomainHandlers<>();
    private final RegisteredDomainHandlers<RegisteredCommandHandler<?, ?>> commandHandlers =
            new RegisteredDomainHandlers<>();


    public static HandlerRegistry register() {
        HandlerRegistry instance = new HandlerRegistry();
        instance.domainEventListeners.put(DEFAULT_DOMAIN, new CopyOnWriteArrayList<>());
        return instance;
    }

    //events: DomainEvent
    public <T> HandlerRegistry listenEvent(String eventName, DomainEventHandler<T> handler, Class<T> eventClass) {
        return listenDomainEvent(DEFAULT_DOMAIN, eventName, handler, eventClass);
    }

    public <T> HandlerRegistry listenDomainEvent(String domain, String eventName, DomainEventHandler<T> handler,
                                                 Class<T> eventClass) {
        domainEventListeners.add(domain, new RegisteredEventListener<>(eventName, handler, eventClass));
        return this;
    }

    // events: CloudEvent
    public HandlerRegistry listenCloudEvent(String eventName, CloudEventHandler handler) {
        return listenDomainCloudEvent(DEFAULT_DOMAIN, eventName, handler);
    }

    public HandlerRegistry listenDomainCloudEvent(String domain, String eventName, CloudEventHandler handler) {
        domainEventListeners.add(domain, new RegisteredEventListener<>(eventName, handler, CloudEvent.class));
        return this;
    }

    // events: RawMessage
    public HandlerRegistry listenRawEvent(String eventName, RawEventHandler<?> handler) {
        return listenDomainRawEvent(DEFAULT_DOMAIN, eventName, handler);
    }

    public HandlerRegistry listenDomainRawEvent(String domain, String eventName, RawEventHandler<?> handler) {
        domainEventListeners.add(domain, new RegisteredEventListener<>(eventName, handler, RawMessage.class));
        return this;
    }

    // notifications: DomainEvent
    public <T> HandlerRegistry listenNotificationEvent(String eventName, DomainEventHandler<T> handler,
                                                       Class<T> eventClass) {
        return listenNotificationEvent(DEFAULT_DOMAIN, eventName, handler, eventClass);
    }

    public <T> HandlerRegistry listenNotificationEvent(String domain, String eventName, DomainEventHandler<T> handler,
                                                       Class<T> eventClass) {
        eventNotificationListener.add(domain, new RegisteredEventListener<>(eventName, handler, eventClass));
        return this;
    }

    // notifications: CloudEvent
    public HandlerRegistry listenNotificationCloudEvent(String eventName, CloudEventHandler handler) {
        return listenNotificationCloudEvent(DEFAULT_DOMAIN, eventName, handler);
    }

    public HandlerRegistry listenNotificationCloudEvent(String domain, String eventName, CloudEventHandler handler) {
        eventNotificationListener.add(domain, new RegisteredEventListener<>(eventName, handler, CloudEvent.class));
        return this;
    }

    // notifications: RawMessage
    public HandlerRegistry listenNotificationRawEvent(String eventName, RawEventHandler<?> handler) {
        return listenDomainRawEvent(DEFAULT_DOMAIN, eventName, handler);
    }

    public HandlerRegistry listenNotificationRawEvent(String domain, String eventName, RawEventHandler<?> handler) {
        eventNotificationListener.add(domain, new RegisteredEventListener<>(eventName, handler, RawMessage.class));
        return this;
    }

    // dynamic: DomainEvent supported only for default domain
    public <T> HandlerRegistry handleDynamicEvents(String eventNamePattern, DomainEventHandler<T> handler,
                                                   Class<T> eventClass) {
        dynamicEventHandlers.add(DEFAULT_DOMAIN, new RegisteredEventListener<>(eventNamePattern, handler, eventClass));
        return this;
    }

    // dynamic: CloudEvent supported only for default domain
    public HandlerRegistry handleDynamicCloudEvents(String eventNamePattern, CloudEventHandler handler) {
        dynamicEventHandlers.add(DEFAULT_DOMAIN, new RegisteredEventListener<>(eventNamePattern, handler,
                CloudEvent.class));
        return this;
    }

    // commands: Command
    public <T> HandlerRegistry handleCommand(String commandName, DomainCommandHandler<T> fn, Class<T> commandClass) {
        return handleCommand(DEFAULT_DOMAIN, commandName, fn, commandClass);
    }

    public <T> HandlerRegistry handleCommand(String domain, String commandName, DomainCommandHandler<T> fn,
                                             Class<T> commandClass) {
        commandHandlers.add(domain, new RegisteredCommandHandler<>(commandName, fn, commandClass));
        return this;
    }

    // commands: CloudEvent
    public HandlerRegistry handleCloudEventCommand(String commandName, CloudCommandHandler handler) {
        return handleCloudEventCommand(DEFAULT_DOMAIN, commandName, handler);
    }

    public HandlerRegistry handleCloudEventCommand(String domain, String commandName, CloudCommandHandler handler) {
        commandHandlers.add(domain, new RegisteredCommandHandler<>(commandName, handler, CloudEvent.class));
        return this;
    }

    // commands: RawMessage
    public HandlerRegistry handleRawCommand(String commandName, RawCommandHandler<?> handler) {
        return handleRawCommand(DEFAULT_DOMAIN, commandName, handler);
    }

    public HandlerRegistry handleRawCommand(String domain, String commandName, RawCommandHandler<?> handler) {
        commandHandlers.add(domain, new RegisteredCommandHandler<>(commandName, handler, RawMessage.class));
        return this;
    }

    // queries: Query
    public <T, R> HandlerRegistry serveQuery(String resource, QueryHandler<T, R> handler, Class<R> queryClass) {
        handlers.add(DEFAULT_DOMAIN, new RegisteredQueryHandler<>(resource, (ignored, message) ->
                handler.handle(message), queryClass
        ));
        return this;
    }

    public <R> HandlerRegistry serveQuery(String resource, QueryHandlerDelegate<Void, R> handler, Class<R> queryClass) {
        handlers.add(DEFAULT_DOMAIN, new RegisteredQueryHandler<>(resource, handler, queryClass));
        return this;
    }

    public <R> HandlerRegistry serveCloudEventQuery(String resource, QueryHandler<R, CloudEvent> handler) {
        handlers.add(DEFAULT_DOMAIN, new RegisteredQueryHandler<>(resource, (ignored, message) ->
                handler.handle(message), CloudEvent.class
        ));
        return this;
    }

    public HandlerRegistry serveCloudEventQuery(String resource, QueryHandlerDelegate<Void, CloudEvent> handler) {
        handlers.add(DEFAULT_DOMAIN, new RegisteredQueryHandler<>(resource, handler, CloudEvent.class));
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
        commandHandlers.add(DEFAULT_DOMAIN, new RegisteredCommandHandler<>(commandName, handler,
                inferGenericParameterType(handler)));
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



