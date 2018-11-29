package org.reactivecommons.async.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import lombok.Data;
import org.junit.Test;
import org.mockito.junit.MockitoJUnitRunner;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import reactor.core.publisher.Mono;

public class HandlerRegistryTest {

    private HandlerRegistry registry = HandlerRegistry.register();

    @Test
    public void shouldListenEventWithTypeInferenceWhenClassInstanceIsUsed(){
        String eventName = "some.event";
        SomeEventHandler eventHandler = new SomeEventHandler();

        registry.listenEvent(eventName, eventHandler);

        assertThat(registry.getEventListeners()).anySatisfy(registered -> {
            assertThat(registered).extracting(RegisteredEventListener::getPath, RegisteredEventListener::getInputClass, RegisteredEventListener::getHandler)
                .containsExactly(eventName, SomeDataClass.class, eventHandler);
        }).hasSize(1);
    }


    private static class SomeEventHandler implements EventHandler<SomeDataClass> {
        @Override
        public Mono<Void> handle(DomainEvent<SomeDataClass> message) {
            return Mono.empty();
        }
    }

    @Data
    private static class SomeDataClass {
        private String someProp1;
        private Integer someProp2;
    }

}