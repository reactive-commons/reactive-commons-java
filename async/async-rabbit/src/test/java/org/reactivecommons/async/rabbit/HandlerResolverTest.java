package org.reactivecommons.async.rabbit;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class HandlerResolverTest {
    private HandlerResolver resolver;

    @BeforeEach
    void setup() {
        Map<String, RegisteredCommandHandler<?>> commandHandlers = new ConcurrentHashMap<>();
        Map<String, RegisteredEventListener<?>> eventListeners = new ConcurrentHashMap<>();
        eventListeners.put("event.name", new RegisteredEventListener<>("event.name", message -> Mono.empty(), String.class));
        eventListeners.put("event.name2", new RegisteredEventListener<>("event.name2", message -> Mono.empty(), String.class));
        eventListeners.put("some.*", new RegisteredEventListener<>("some.*", message -> Mono.empty(), String.class));
        Map<String, RegisteredEventListener<?>> eventsToBind = new ConcurrentHashMap<>();
        eventsToBind.put("event.name", new RegisteredEventListener<>("event.name", message -> Mono.empty(), String.class));
        eventsToBind.put("event.name2", new RegisteredEventListener<>("event.name2", message -> Mono.empty(), String.class));
        Map<String, RegisteredEventListener<?>> notificationEventListeners = new ConcurrentHashMap<>();
        Map<String, RegisteredQueryHandler<?, ?>> queryHandlers = new ConcurrentHashMap<>();
        resolver = new HandlerResolver(queryHandlers, eventListeners, eventsToBind, notificationEventListeners, commandHandlers);
    }

    @Test
    void shouldGetOnlyTheBindingEvents() {
        // Act
        Collection<RegisteredEventListener<?>> eventListener = resolver.getEventListeners();
        // Assert
        Assertions.assertThat(eventListener.size()).isEqualTo(2);
    }

    @Test
    void shouldMatchForAWildcardEvent() {
        // Act
        RegisteredEventListener<Object> eventListener = resolver.getEventListener("some.sample");
        // Assert
        Assertions.assertThat(eventListener.getPath()).isEqualTo("some.*");
    }

    @Test
    void shouldMatchForAnExactEvent() {
        // Act
        RegisteredEventListener<Object> eventListener = resolver.getEventListener("event.name");
        // Assert
        Assertions.assertThat(eventListener.getPath()).isEqualTo("event.name");
    }

}
