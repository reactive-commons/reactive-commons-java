package org.reactivecommons.async.api;

import io.cloudevents.CloudEvent;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
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
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

class HandlerRegistryTest {
    private final HandlerRegistry registry = HandlerRegistry.register();
    private final String name = "some.event";
    private final String nameRaw = "some.raw.event";
    private final String nameRawNotification = "some.raw.notification.event";
    private final String domain = "some-domain";


    @Test
    void shouldListenDomainEvent() {
        SomeDomainEventHandler<SomeDataClass> eventHandler = new SomeDomainEventHandler<>();

        registry.listenDomainEvent(domain, name, eventHandler, SomeDataClass.class);

        assertThat(registry.getDomainEventListeners().get(domain))
                .anySatisfy(registered -> assertThat(registered)
                        .extracting(RegisteredEventListener::getPath, RegisteredEventListener::getInputClass,
                                RegisteredEventListener::getHandler
                        )
                        .containsExactly(name, SomeDataClass.class, eventHandler)).hasSize(1);
    }

    @Test
    void shouldListenDomainCloudEvent() {
        SomeCloudEventHandler eventHandler = new SomeCloudEventHandler();

        registry.listenDomainCloudEvent(domain, name, eventHandler);

        assertThat(registry.getDomainEventListeners().get(domain))
                .anySatisfy(registered -> assertThat(registered)
                        .extracting(RegisteredEventListener::getPath, RegisteredEventListener::getInputClass,
                                RegisteredEventListener::getHandler
                        )
                        .containsExactly(name, CloudEvent.class, eventHandler)).hasSize(1);
    }

    @Test
    void shouldListenDomainRawEvent() {
        SomeRawEventHandler eventHandler = new SomeRawEventHandler();

        registry.listenRawEvent(name, eventHandler);

        assertThat(registry.getDomainEventListeners().get(DEFAULT_DOMAIN))
                .anySatisfy(registered -> assertThat(registered)
                        .extracting(RegisteredEventListener::getPath, RegisteredEventListener::getInputClass,
                                RegisteredEventListener::getHandler
                        )
                        .containsExactly(name, RawMessage.class, eventHandler)).hasSize(1);
    }

    @Test
    void shouldListenEvent() {
        SomeDomainEventHandler<SomeDataClass> eventHandler = new SomeDomainEventHandler<>();

        registry.listenEvent(name, eventHandler, SomeDataClass.class);

        assertThat(registry.getDomainEventListeners().get(DEFAULT_DOMAIN))
                .anySatisfy(registered -> assertThat(registered)
                        .extracting(RegisteredEventListener::getPath, RegisteredEventListener::getInputClass,
                                RegisteredEventListener::getHandler
                        )
                        .containsExactly(name, SomeDataClass.class, eventHandler)).hasSize(1);
    }

    @Test
    void shouldListenCloudEvent() {
        SomeCloudEventHandler eventHandler = new SomeCloudEventHandler();

        registry.listenCloudEvent(name, eventHandler);

        assertThat(registry.getDomainEventListeners().get(DEFAULT_DOMAIN))
                .anySatisfy(registered -> assertThat(registered)
                        .extracting(RegisteredEventListener::getPath, RegisteredEventListener::getInputClass,
                                RegisteredEventListener::getHandler
                        )
                        .containsExactly(name, CloudEvent.class, eventHandler)).hasSize(1);
    }

    @Test
    void shouldRegisterPatternEventHandler() {
        SomeDomainEventHandler<SomeDataClass> eventHandler = new SomeDomainEventHandler<>();

        String eventNamePattern = "a.*";

        HandlerRegistry resultRegistry = registry.listenEvent(eventNamePattern, eventHandler, SomeDataClass.class);
        RegisteredEventListener<SomeDataClass, DomainEvent<SomeDataClass>> expectedRegisteredEventListener =
                new RegisteredEventListener<>(eventNamePattern, eventHandler, SomeDataClass.class);

        assertThat(registry.getDomainEventListeners().get(DEFAULT_DOMAIN))
                .anySatisfy(registeredEventListener -> assertThat(registeredEventListener)
                        .usingRecursiveComparison()
                        .isEqualTo(expectedRegisteredEventListener));

        assertThat(resultRegistry)
                .isSameAs(registry);
    }

    @Test
    void shouldRegisterNotificationEventListener() {
        registry.listenNotificationEvent(name, message -> Mono.empty(), SomeDataClass.class);
        assertThat(registry.getEventNotificationListener().get(DEFAULT_DOMAIN))
                .anySatisfy(listener -> assertThat(listener.getPath()).isEqualTo(name));
    }

    @Test
    void shouldRegisterNotificationCloudEventListener() {
        registry.listenNotificationCloudEvent(name, message -> Mono.empty());
        assertThat(registry.getEventNotificationListener().get(DEFAULT_DOMAIN))
                .anySatisfy(listener -> assertThat(listener.getPath()).isEqualTo(name));
    }

    @Test
    void shouldRegisterNotificationRawEventListener() {
        SomeRawEventHandler eventHandler = new SomeRawEventHandler();

        registry.listenNotificationRawEvent(nameRawNotification, eventHandler);

        assertThat(registry.getEventNotificationListener().get(DEFAULT_DOMAIN))
                .anySatisfy(registered -> assertThat(registered)
                        .extracting(RegisteredEventListener::getPath, RegisteredEventListener::getInputClass,
                                RegisteredEventListener::getHandler
                        )
                        .containsExactly(nameRawNotification, RawMessage.class, eventHandler)).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listenEvent() {
        SomeDomainEventHandler<SomeDataClass> handler = mock(SomeDomainEventHandler.class);
        registry.listenEvent(name, handler, SomeDataClass.class);

        assertThat(registry.getDomainEventListeners().get(DEFAULT_DOMAIN))
                .anySatisfy(registered -> assertThat(registered)
                        .extracting(RegisteredEventListener::getPath, RegisteredEventListener::getInputClass,
                                RegisteredEventListener::getHandler
                        )
                        .containsExactly(name, SomeDataClass.class, handler)).hasSize(1);
    }

    @Test
    void shouldListenDynamicEvent() {
        SomeDomainEventHandler<SomeDataClass> eventHandler = new SomeDomainEventHandler<>();

        registry.handleDynamicEvents(name, eventHandler, SomeDataClass.class);

        assertThat(registry.getDynamicEventHandlers().get(DEFAULT_DOMAIN))
                .anySatisfy(registered -> assertThat(registered)
                        .extracting(RegisteredEventListener::getPath, RegisteredEventListener::getInputClass,
                                RegisteredEventListener::getHandler
                        )
                        .containsExactly(name, SomeDataClass.class, eventHandler)).hasSize(1);
    }

    @Test
    void shouldListenDynamicCloudEvent() {
        SomeCloudEventHandler eventHandler = new SomeCloudEventHandler();

        registry.handleDynamicCloudEvents(name, eventHandler);

        assertThat(registry.getDynamicEventHandlers().get(DEFAULT_DOMAIN))
                .anySatisfy(registered -> assertThat(registered)
                        .extracting(RegisteredEventListener::getPath, RegisteredEventListener::getInputClass,
                                RegisteredEventListener::getHandler
                        )
                        .containsExactly(name, CloudEvent.class, eventHandler)).hasSize(1);
    }

    @Test
    void handleDomainCommand() {
        SomeDomainCommandHandler<SomeDataClass> handler = new SomeDomainCommandHandler<>();

        registry.handleCommand(name, handler, SomeDataClass.class);

        assertThat(registry.getCommandHandlers().get(DEFAULT_DOMAIN))
                .anySatisfy(registered -> assertThat(registered)
                        .extracting(RegisteredCommandHandler::getPath, RegisteredCommandHandler::getInputClass,
                                RegisteredCommandHandler::getHandler
                        )
                        .containsExactly(name, SomeDataClass.class, handler)).hasSize(1);
    }

    @Test
    void handleCloudEventCommand() {
        SomeCloudCommandHandler cloudCommandHandler = new SomeCloudCommandHandler();

        registry.handleCloudEventCommand(name, cloudCommandHandler);

        assertThat(registry.getCommandHandlers().get(DEFAULT_DOMAIN))
                .anySatisfy(registered -> assertThat(registered)
                        .extracting(RegisteredCommandHandler::getPath, RegisteredCommandHandler::getInputClass,
                                RegisteredCommandHandler::getHandler
                        )
                        .containsExactly(name, CloudEvent.class, cloudCommandHandler)).hasSize(1);
    }

    @Test
    void handleRawCommand() {
        SomeRawCommandEventHandler eventHandler = new SomeRawCommandEventHandler();

        registry.handleRawCommand(nameRaw, eventHandler);

        assertThat(registry.getCommandHandlers().get(DEFAULT_DOMAIN))
                .anySatisfy(registered -> assertThat(registered)
                        .extracting(RegisteredCommandHandler::getPath, RegisteredCommandHandler::getInputClass,
                                RegisteredCommandHandler::getHandler
                        )
                        .containsExactly(nameRaw, RawMessage.class, eventHandler)).hasSize(1);
    }

    @Test
    void shouldServerCloudEventQuery() {
        SomeCloudEventQueryHandler queryHandler = new SomeCloudEventQueryHandler();

        registry.serveCloudEventQuery(name, queryHandler);

        assertThat(registry.getHandlers().get(DEFAULT_DOMAIN))
                .anySatisfy(registered -> assertThat(registered)
                        .extracting(RegisteredQueryHandler::getPath, RegisteredQueryHandler::getQueryClass)
                        .containsExactly(name, CloudEvent.class)).hasSize(1);
    }

    @Test
    void handleCommandWithLambda() {
        registry.handleCommand(name, (Command<SomeDataClass> message) -> Mono.empty(), SomeDataClass.class);

        assertThat(registry.getCommandHandlers().get(DEFAULT_DOMAIN))
                .anySatisfy(registered -> assertThat(registered)
                        .extracting(RegisteredCommandHandler::getPath, RegisteredCommandHandler::getInputClass)
                        .containsExactly(name, SomeDataClass.class)).hasSize(1);
    }


    @Test
    void serveQueryWithLambda() {
        registry.serveQuery(name, message -> Mono.empty(), SomeDataClass.class);
        assertThat(registry.getHandlers().get(DEFAULT_DOMAIN))
                .anySatisfy(registered -> assertThat(registered)
                        .extracting(RegisteredQueryHandler::getPath, RegisteredQueryHandler::getQueryClass)
                        .containsExactly(name, SomeDataClass.class)).hasSize(1);
    }

    @Test
    void serveQueryWithTypeInference() {
        QueryHandler<SomeDataClass, SomeDataClass> handler = new SomeQueryHandler();
        registry.serveQuery(name, handler, SomeDataClass.class);
        assertThat(registry.getHandlers().get(DEFAULT_DOMAIN)).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredQueryHandler::getPath, RegisteredQueryHandler::getQueryClass)
                    .containsExactly(name, SomeDataClass.class);
            assertThat(registered).extracting(RegisteredQueryHandler::getHandler)
                    .isInstanceOf(QueryHandlerDelegate.class);
        }).hasSize(1);
    }

    @Test
    void serveQueryDelegate() {
        QueryHandlerDelegate<Void, SomeDataClass> handler = new SomeQueryHandlerDelegate();
        registry.serveQuery(name, handler, SomeDataClass.class);
        assertThat(registry.getHandlers().get(DEFAULT_DOMAIN)).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredQueryHandler::getPath, RegisteredQueryHandler::getQueryClass)
                    .containsExactly(name, SomeDataClass.class);
        }).hasSize(1);
    }

    @Test
    void serveQueryDelegateWithLambda() {
        registry.serveQuery(name, (from, message) -> Mono.empty(), SomeDataClass.class);
        assertThat(registry.getHandlers().get(DEFAULT_DOMAIN)).anySatisfy(registered -> {
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

    private static class SomeDomainEventHandler<SomeDataClass> implements DomainEventHandler<SomeDataClass> {
        @Override
        public Mono<Void> handle(DomainEvent<SomeDataClass> message) {
            return Mono.empty();
        }
    }

    private static class SomeCloudEventHandler implements CloudEventHandler {
        @Override
        public Mono<Void> handle(CloudEvent message) {
            return null;
        }
    }

    private static class SomeRawEventHandler implements RawEventHandler<RawMessage> {
        @Override
        public Mono<Void> handle(RawMessage message) {
            return null;
        }
    }

    private static class SomeDomainCommandHandler<SomeDataClass> implements DomainCommandHandler<SomeDataClass> {
        @Override
        public Mono<Void> handle(Command<SomeDataClass> message) {
            return Mono.empty();
        }
    }

    private static class SomeCloudCommandHandler implements CloudCommandHandler {
        @Override
        public Mono<Void> handle(CloudEvent message) {
            return null;
        }
    }

    private static class SomeRawCommandEventHandler implements RawCommandHandler<RawMessage> {
        @Override
        public Mono<Void> handle(RawMessage message) {
            return Mono.empty();
        }
    }

    private static class SomeQueryHandler implements QueryHandler<SomeDataClass, SomeDataClass> {
        @Override
        public Mono<SomeDataClass> handle(SomeDataClass message) {
            return Mono.empty();
        }
    }

    private static class SomeCloudEventQueryHandler implements QueryHandler<SomeDataClass, CloudEvent> {
        @Override
        public Mono<SomeDataClass> handle(CloudEvent message) {
            return Mono.empty();
        }
    }

    @Data
    private static class SomeDataClass {
        private String someProp1;
        private Integer someProp2;
    }

}
