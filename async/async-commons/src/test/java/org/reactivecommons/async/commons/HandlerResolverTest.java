package org.reactivecommons.async.commons;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueueListener;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class HandlerResolverTest {

    Map<String, RegisteredQueryHandler<?, ?>> queryHandlers;
    Map<String, RegisteredEventListener<?, ?>> eventListeners;
    Map<String, RegisteredEventListener<?, ?>> eventsToBind;
    Map<String, RegisteredEventListener<?, ?>> notificationListeners;
    Map<String, RegisteredCommandHandler<?, ?>> commandHandlers;
    Map<String, RegisteredQueueListener> queueListeners;
    HandlerResolver resolver;

    @BeforeEach
    void setUp() {
        queryHandlers = new ConcurrentHashMap<>();
        eventListeners = new ConcurrentHashMap<>();
        eventsToBind = new ConcurrentHashMap<>();
        notificationListeners = new ConcurrentHashMap<>();
        commandHandlers = new ConcurrentHashMap<>();
        queueListeners = new ConcurrentHashMap<>();
        resolver = new HandlerResolver(queryHandlers, eventListeners, eventsToBind,
                notificationListeners, commandHandlers, queueListeners);
    }

    @Test
    void hasNotificationListenersWhenEmpty() {
        assertThat(resolver.hasNotificationListeners()).isFalse();
    }

    @Test
    void hasNotificationListenersWhenPresent() {
        notificationListeners.put("event.test", new RegisteredEventListener<>("event.test", mock(), Object.class));
        resolver = new HandlerResolver(queryHandlers, eventListeners, eventsToBind,
                notificationListeners, commandHandlers, queueListeners);
        assertThat(resolver.hasNotificationListeners()).isTrue();
    }

    @Test
    void hasCommandHandlersWhenEmpty() {
        assertThat(resolver.hasCommandHandlers()).isFalse();
    }

    @Test
    void hasCommandHandlersWhenPresent() {
        commandHandlers.put("cmd.test", new RegisteredCommandHandler<>("cmd.test", mock(), Object.class));
        resolver = new HandlerResolver(queryHandlers, eventListeners, eventsToBind,
                notificationListeners, commandHandlers, queueListeners);
        assertThat(resolver.hasCommandHandlers()).isTrue();
    }

    @Test
    void hasQueryHandlersWhenEmpty() {
        assertThat(resolver.hasQueryHandlers()).isFalse();
    }

    @Test
    void hasQueryHandlersWhenPresent() {
        queryHandlers.put("query.test", new RegisteredQueryHandler<>("query.test", mock(), Object.class));
        resolver = new HandlerResolver(queryHandlers, eventListeners, eventsToBind,
                notificationListeners, commandHandlers, queueListeners);
        assertThat(resolver.hasQueryHandlers()).isTrue();
    }

    @Test
    void getQueryHandlerReturnsRegistered() {
        var handler = new RegisteredQueryHandler<>("query.test", mock(), Object.class);
        queryHandlers.put("query.test", handler);
        assertThat(resolver.getQueryHandler("query.test")).isSameAs(handler);
    }

    @Test
    void getCommandHandlerReturnsRegistered() {
        var handler = new RegisteredCommandHandler<>("cmd.test", mock(), Object.class);
        commandHandlers.put("cmd.test", handler);
        assertThat(resolver.getCommandHandler("cmd.test")).isSameAs(handler);
    }

    @Test
    void getEventListenerReturnsRegistered() {
        var handler = new RegisteredEventListener<>("event.test", mock(), Object.class);
        eventListeners.put("event.test", handler);
        assertThat(resolver.getEventListener("event.test")).isSameAs(handler);
    }

    @Test
    void getEventListenerUsesMatcherForUnknownPath() {
        var handler = new RegisteredEventListener<>("event.*", mock(), Object.class);
        eventListeners.put("event.*", handler);
        var result = resolver.getEventListener("event.foo");
        assertThat(result).isSameAs(handler);
    }

    @Test
    void getNotificationListeners() {
        var handler = new RegisteredEventListener<>("notif.test", mock(), Object.class);
        notificationListeners.put("notif.test", handler);
        resolver = new HandlerResolver(queryHandlers, eventListeners, eventsToBind,
                notificationListeners, commandHandlers, queueListeners);
        Collection<RegisteredEventListener<?, ?>> listeners = resolver.getNotificationListeners();
        assertThat(listeners).hasSize(1);
    }

    @Test
    void getNotificationListener() {
        var handler = new RegisteredEventListener<>("notif.test", mock(), Object.class);
        notificationListeners.put("notif.test", handler);
        resolver = new HandlerResolver(queryHandlers, eventListeners, eventsToBind,
                notificationListeners, commandHandlers, queueListeners);
        assertThat(resolver.getNotificationListener("notif.test")).isSameAs(handler);
    }

    @Test
    void getEventListenersReturnsEventsToBindValues() {
        var handler = new RegisteredEventListener<>("bind.test", mock(), Object.class);
        eventsToBind.put("bind.test", handler);
        assertThat(resolver.getEventListeners()).containsExactly(handler);
    }

    @Test
    void getEventNames() {
        eventListeners.put("event.a", new RegisteredEventListener<>("event.a", mock(), Object.class));
        eventListeners.put("event.b", new RegisteredEventListener<>("event.b", mock(), Object.class));
        List<String> names = resolver.getEventNames();
        assertThat(names).containsExactlyInAnyOrder("event.a", "event.b");
    }

    @Test
    void getNotificationNames() {
        notificationListeners.put("n.a", new RegisteredEventListener<>("n.a", mock(), Object.class));
        resolver = new HandlerResolver(queryHandlers, eventListeners, eventsToBind,
                notificationListeners, commandHandlers, queueListeners);
        assertThat(resolver.getNotificationNames()).containsExactly("n.a");
    }

    @Test
    void addEventListener() {
        var handler = new RegisteredEventListener<>("dynamic.event", mock(), Object.class);
        resolver.addEventListener(handler);
        assertThat(resolver.getEventListener("dynamic.event")).isSameAs(handler);
    }

    @Test
    void addQueryHandler() {
        var handler = new RegisteredQueryHandler<>("query.new", mock(), Object.class);
        resolver.addQueryHandler(handler);
        assertThat(resolver.getQueryHandler("query.new")).isSameAs(handler);
    }

    @Test
    void addQueryHandlerRejectsWildcard() {
        var handler = new RegisteredQueryHandler<>("query.*", mock(), Object.class);
        assertThatThrownBy(() -> resolver.addQueryHandler(handler))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("avoid * or #");
    }

    @Test
    void addQueryHandlerRejectsHash() {
        var handler = new RegisteredQueryHandler<>("query.#", mock(), Object.class);
        assertThatThrownBy(() -> resolver.addQueryHandler(handler))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("avoid * or #");
    }

    @Test
    void getQueueListeners() {
        var ql = new RegisteredQueueListener("queue", mock(), mock());
        queueListeners.put("queue", ql);
        resolver = new HandlerResolver(queryHandlers, eventListeners, eventsToBind,
                notificationListeners, commandHandlers, queueListeners);
        assertThat(resolver.getQueueListeners()).containsEntry("queue", ql);
    }
}
