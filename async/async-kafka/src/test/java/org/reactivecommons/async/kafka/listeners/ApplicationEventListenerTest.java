package org.reactivecommons.async.kafka.listeners;

import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.handlers.CloudEventHandler;
import org.reactivecommons.async.api.handlers.DomainEventHandler;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"rawtypes", "unchecked"})
@ExtendWith(MockitoExtension.class)
class ApplicationEventListenerTest {

    @Mock
    private ReactiveMessageListener receiver;
    @Mock
    private HandlerResolver resolver;
    @Mock
    private MessageConverter messageConverter;
    @Mock
    private Message message;

    private ApplicationEventListener applicationEventListener;

    @BeforeEach
    void setup() {
        applicationEventListener = new ApplicationEventListener(
                receiver,
                resolver,
                messageConverter,
                true,
                true,
                3,
                1000,
                null,
                null,
                "testApp"
        );

    }

    @Test
    void shouldHandleRawMessageSuccessfully() {
        DomainEvent<String> event = new DomainEvent<>("sample", "id", "data");
        DomainEventHandler domainEventHandler = mock(DomainEventHandler.class);
        when(domainEventHandler.handle(event)).thenReturn(Mono.just("Handled"));
        RegisteredEventListener<Object, Object> registeredEventListenerMock = mock(RegisteredEventListener.class);
        when(registeredEventListenerMock.getHandler()).thenReturn(domainEventHandler);
        when(registeredEventListenerMock.getInputClass()).thenReturn(Object.class);
        when(resolver.getEventListener(anyString())).thenReturn(registeredEventListenerMock);
        when(messageConverter.readDomainEvent(any(Message.class), any(Class.class))).thenReturn(event);

        Mono<Object> flow = applicationEventListener.rawMessageHandler("executorPath").apply(message);

        StepVerifier.create(flow)
                .expectNext("Handled")
                .verifyComplete();

        verify(resolver, times(1)).getEventListener(anyString());
        verify(messageConverter, times(1)).readDomainEvent(any(Message.class), any(Class.class));
    }

    @Test
    void shouldHandleRawMessageSuccessfullyWhenCloudEvent() {
        CloudEvent event = mock(CloudEvent.class);
        EventHandler domainEventHandler = mock(CloudEventHandler.class);
        when(domainEventHandler.handle(event)).thenReturn(Mono.empty());
        RegisteredEventListener<Object, Object> registeredEventListenerMock = mock(RegisteredEventListener.class);
        when(registeredEventListenerMock.getHandler()).thenReturn(domainEventHandler);
        when(resolver.getEventListener(anyString())).thenReturn(registeredEventListenerMock);
        when(messageConverter.readCloudEvent(any(Message.class))).thenReturn(event);

        Mono<Object> flow = applicationEventListener.rawMessageHandler("executorPath").apply(message);

        StepVerifier.create(flow)
                .verifyComplete();

        verify(resolver, times(1)).getEventListener(anyString());
        verify(messageConverter, times(1)).readCloudEvent(any(Message.class));
    }
}