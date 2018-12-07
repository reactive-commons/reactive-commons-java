package org.reactivecommons.async.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import lombok.Data;
import org.junit.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.handlers.CommandHandler;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import reactor.core.publisher.Mono;

public class HandlerRegistryTest {

    private HandlerRegistry registry = HandlerRegistry.register();
    private String name = "some.event";

    @Test
    public void shouldListenEventWithTypeInferenceWhenClassInstanceIsUsed(){
        SomeEventHandler eventHandler = new SomeEventHandler();

        registry.listenEvent(name, eventHandler);

        assertThat(registry.getEventListeners()).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredEventListener::getPath, RegisteredEventListener::getInputClass, RegisteredEventListener::getHandler)
                .containsExactly(name, SomeDataClass.class, eventHandler);
        }).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void listenEvent() {
        EventHandler<SomeDataClass> handler = mock(EventHandler.class);
        registry.listenEvent(name, handler, SomeDataClass.class);

        assertThat(registry.getEventListeners()).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredEventListener::getPath, RegisteredEventListener::getInputClass, RegisteredEventListener::getHandler)
                .containsExactly(name, SomeDataClass.class, handler);
        }).hasSize(1);
    }

    @Test
    public void handleCommandWithTypeInference() {
        SomeCommandHandler handler = new SomeCommandHandler();

        registry.handleCommand(name, handler);

        assertThat(registry.getCommandHandlers()).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredCommandHandler::getPath, RegisteredCommandHandler::getInputClass, RegisteredCommandHandler::getHandler)
                .containsExactly(name, SomeDataClass.class, handler);
        }).hasSize(1);
    }

    @Test(expected = RuntimeException.class)
    public void handleCommandWithoutTypeShouldFail() {
        registry.handleCommand(name, (Command<SomeDataClass> message) -> Mono.empty());
    }

    @Test(expected = RuntimeException.class)
    public void listenEventWithoutTypeShouldFail() {
        registry.listenEvent(name, (DomainEvent<SomeDataClass> message) -> Mono.empty());
    }

    @Test(expected = RuntimeException.class)
    public void handleQueryWithoutTypeShouldFail() {
        registry.serveQuery(name, (SomeDataClass query) -> Mono.empty());
    }

    @Test
    public void handleCommandWithLambda() {
        registry.handleCommand(name, (Command<SomeDataClass> message) -> Mono.empty(), SomeDataClass.class);

        assertThat(registry.getCommandHandlers()).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredCommandHandler::getPath, RegisteredCommandHandler::getInputClass)
                .containsExactly(name, SomeDataClass.class);
        }).hasSize(1);
    }


    @Test
    public void serveQueryWithLambda() {
        registry.serveQuery(name, message -> Mono.empty(), SomeDataClass.class);
        assertThat(registry.getHandlers()).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredQueryHandler::getPath, RegisteredQueryHandler::getQueryClass)
                .containsExactly(name, SomeDataClass.class);
        }).hasSize(1);
    }

    @Test
    public void serveQueryWithTypeInference() {
        QueryHandler<SomeDataClass, SomeDataClass> handler = new SomeQueryHandler();
        registry.serveQuery(name, handler);
        assertThat(registry.getHandlers()).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredQueryHandler::getPath, RegisteredQueryHandler::getQueryClass, RegisteredQueryHandler::getHandler)
                .containsExactly(name, SomeDataClass.class, handler);
        }).hasSize(1);
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