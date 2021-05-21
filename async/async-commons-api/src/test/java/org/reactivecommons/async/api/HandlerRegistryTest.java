package org.reactivecommons.async.api;

import lombok.Data;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.handlers.CommandHandler;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.api.handlers.QueryHandlerDelegate;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class HandlerRegistryTest {

    private HandlerRegistry registry = HandlerRegistry.register();
    private String name = "some.event";

    @Test
    void shouldListenEventWithTypeInferenceWhenClassInstanceIsUsed() {
        SomeEventHandler eventHandler = new SomeEventHandler();

        registry.listenEvent(name, eventHandler);

        assertThat(registry.getEventListeners()).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredEventListener::getPath,
                    RegisteredEventListener::getInputClass, RegisteredEventListener::getHandler)
                    .containsExactly(name, SomeDataClass.class, eventHandler);
        }).hasSize(1);
    }

    @Test
    void shouldRegisterDynamicEventsHandlerWithTypeInference() {
        SomeEventHandler eventHandler = new SomeEventHandler();

        String eventNamePattern = "a.*";

        HandlerRegistry resultRegistry = registry.handleDynamicEvents(eventNamePattern, eventHandler);
        RegisteredEventListener<SomeDataClass> expectedRegisteredEventListener =
                new RegisteredEventListener<>(eventNamePattern, eventHandler, SomeDataClass.class);

        assertThat(registry.getDynamicEventsHandlers())
                .anySatisfy(registeredEventListener -> {
            assertThat(registeredEventListener)
                    .usingRecursiveComparison()
                    .isEqualTo(expectedRegisteredEventListener);
        });

        assertThat(resultRegistry)
                .isSameAs(registry);
    }

    @Test
    void shouldRegisterDynamicEventsHandler() {
        SomeEventHandler eventHandler = new SomeEventHandler();

        String eventNamePattern = "a.*";

        HandlerRegistry resultRegistry = registry.handleDynamicEvents(eventNamePattern, eventHandler, SomeDataClass.class);
        RegisteredEventListener<SomeDataClass> expectedRegisteredEventListener =
                new RegisteredEventListener<>(eventNamePattern, eventHandler, SomeDataClass.class);

        assertThat(registry.getDynamicEventsHandlers())
                .anySatisfy(registeredEventListener -> {
                    assertThat(registeredEventListener)
                            .usingRecursiveComparison()
                            .isEqualTo(expectedRegisteredEventListener);
                });

        assertThat(resultRegistry)
                .isSameAs(registry);
    }

    @Test
    void shouldRegisterNotificationEventListener() {
        registry.listenNotificationEvent(name, message -> Mono.empty(), SomeDataClass.class);
        assertThat(registry.getEventNotificationListener()).anySatisfy(listener ->
            assertThat(listener.getPath()).isEqualTo(name));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void listenEvent() {
        EventHandler<SomeDataClass> handler = mock(EventHandler.class);
        registry.listenEvent(name, handler, SomeDataClass.class);

        assertThat(registry.getEventListeners()).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredEventListener::getPath,
                    RegisteredEventListener::getInputClass, RegisteredEventListener::getHandler)
                    .containsExactly(name, SomeDataClass.class, handler);
        }).hasSize(1);
    }

    @Test
    void handleCommandWithTypeInference() {
        SomeCommandHandler handler = new SomeCommandHandler();

        registry.handleCommand(name, handler);

        assertThat(registry.getCommandHandlers()).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredCommandHandler::getPath,
                    RegisteredCommandHandler::getInputClass, RegisteredCommandHandler::getHandler)
                    .containsExactly(name, SomeDataClass.class, handler);
        }).hasSize(1);
    }

    @Test
    void handleCommandWithoutTypeShouldFail() {
        Assertions.assertThrows(
                RuntimeException.class,
                () -> registry.handleCommand(name, (Command<SomeDataClass> message) -> Mono.empty()));
    }

    @Test
    void listenEventWithoutTypeShouldFail() {
        Assertions.assertThrows(
                RuntimeException.class,
                () -> registry.listenEvent(name, (DomainEvent<SomeDataClass> message) -> Mono.empty()));
    }

    @Test
    void handleQueryWithoutTypeShouldFail() {
        Assertions.assertThrows(
                RuntimeException.class,
                () -> registry.serveQuery(name, (SomeDataClass query) -> Mono.empty()));
    }

    @Test
    void handleCommandWithLambda() {
        registry.handleCommand(name, (Command<SomeDataClass> message) -> Mono.empty(), SomeDataClass.class);

        assertThat(registry.getCommandHandlers()).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredCommandHandler::getPath,
                    RegisteredCommandHandler::getInputClass)
                    .containsExactly(name, SomeDataClass.class);
        }).hasSize(1);
    }


    @Test
    void serveQueryWithLambda() {
        registry.serveQuery(name, message -> Mono.empty(), SomeDataClass.class);
        assertThat(registry.getHandlers()).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredQueryHandler::getPath, RegisteredQueryHandler::getQueryClass)
                    .containsExactly(name, SomeDataClass.class);
        }).hasSize(1);
    }

    @Test
    void serveQueryWithTypeInference() {
        QueryHandler<SomeDataClass, SomeDataClass> handler = new SomeQueryHandler();
        registry.serveQuery(name, handler);
        assertThat(registry.getHandlers()).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredQueryHandler::getPath, RegisteredQueryHandler::getQueryClass)
                    .containsExactly(name, SomeDataClass.class);
            assertThat(registered).extracting(RegisteredQueryHandler::getHandler).isInstanceOf(QueryHandlerDelegate.class);
        }).hasSize(1);
    }

    @Test
    void serveQueryDelegate() {
        QueryHandlerDelegate<Void, SomeDataClass> handler = new SomeQueryHandlerDelegate();
        registry.serveQuery(name, handler, SomeDataClass.class);
        assertThat(registry.getHandlers()).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredQueryHandler::getPath, RegisteredQueryHandler::getQueryClass)
                    .containsExactly(name, SomeDataClass.class);
        }).hasSize(1);
    }

    @Test
    void serveQueryDelegateWithLambda() {
        registry.serveQuery(name, (from, message) -> Mono.empty(), SomeDataClass.class);
        assertThat(registry.getHandlers()).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredQueryHandler::getPath, RegisteredQueryHandler::getQueryClass)
                    .containsExactly(name, SomeDataClass.class);
        }).hasSize(1);
    }

    private static class SomeQueryHandlerDelegate implements QueryHandlerDelegate<Void, SomeDataClass> {
        @Override
        public Mono<Void> handle(From from, SomeDataClass message) {
            return Mono.empty();
        }
    }

    private static class SomeEventHandler implements EventHandler<SomeDataClass> {
        @Override
        public Mono<Void> handle(DomainEvent<SomeDataClass> message) {
            return Mono.empty();
        }
    }

    private static class SomeCommandHandler implements CommandHandler<SomeDataClass> {
        @Override
        public Mono<Void> handle(Command<SomeDataClass> message) {
            return Mono.empty();
        }
    }

    private static class SomeQueryHandler implements QueryHandler<SomeDataClass, SomeDataClass> {
        @Override
        public Mono<SomeDataClass> handle(SomeDataClass message) {
            return Mono.empty();
        }
    }


    @Data
    private static class SomeDataClass {
        private String someProp1;
        private Integer someProp2;
    }

}
