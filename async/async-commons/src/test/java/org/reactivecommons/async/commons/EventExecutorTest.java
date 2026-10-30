package org.reactivecommons.async.commons;

import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.handlers.CloudEventHandler;
import org.reactivecommons.async.api.handlers.DomainEventHandler;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.RawEventHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventExecutorTest {

    @SuppressWarnings("unchecked")
    @Test
    void executeCallsHandlerWithConvertedMessage() {
        EventHandler<String> handler = mock(EventHandler.class);
        when(handler.handle(any())).thenReturn(Mono.empty());

        EventExecutor<String> executor = new EventExecutor<>(handler, msg -> "converted");
        StepVerifier.create(executor.execute(createMessage()))
                .verifyComplete();
    }

    @Test
    void aDomainEventHandlerReceivesTheMessageReadAsADomainEventOfTheClassOfItsListener() {
        MessageConverter converter = mock(MessageConverter.class);
        Message message = createMessage();
        DomainEvent<String> event = new DomainEvent<>("event.name", "id", "data");
        when(converter.readDomainEvent(message, String.class)).thenReturn(event);
        List<Object> handled = new ArrayList<>();
        DomainEventHandler<String> handler = received -> collect(handled, received);

        StepVerifier.create(EventExecutor.of(listener(handler, String.class), converter).execute(message))
                .verifyComplete();

        assertThat(handled).containsExactly(event);
    }

    @Test
    void aCloudEventHandlerReceivesTheMessageReadAsACloudEvent() {
        MessageConverter converter = mock(MessageConverter.class);
        Message message = createMessage();
        CloudEvent cloudEvent = mock(CloudEvent.class);
        when(converter.readCloudEvent(message)).thenReturn(cloudEvent);
        List<Object> handled = new ArrayList<>();
        CloudEventHandler handler = received -> collect(handled, received);

        StepVerifier.create(EventExecutor.of(listener(handler, CloudEvent.class), converter).execute(message))
                .verifyComplete();

        assertThat(handled).containsExactly(cloudEvent);
    }

    @Test
    void aRawEventHandlerReceivesTheMessageAsItWasReceived() {
        MessageConverter converter = mock(MessageConverter.class);
        Message message = createMessage();
        List<Object> handled = new ArrayList<>();
        RawEventHandler<Message> handler = received -> collect(handled, received);

        StepVerifier.create(EventExecutor.of(listener(handler, Message.class), converter).execute(message))
                .verifyComplete();

        assertThat(handled).containsExactly(message);
        verifyNoInteractions(converter);
    }

    @Test
    void anUnknownHandlerIsRejected() {
        EventHandler<Object> handler = received -> Mono.empty();
        RegisteredEventListener<Object, Object> listener = listener(handler, Object.class);
        MessageConverter converter = mock(MessageConverter.class);

        assertThatThrownBy(() -> EventExecutor.of(listener, converter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown handler type");
    }

    @SuppressWarnings("unchecked")
    private static RegisteredEventListener<Object, Object> listener(EventHandler<?> handler, Class<?> inputClass) {
        return (RegisteredEventListener<Object, Object>) (RegisteredEventListener<?, ?>)
                new RegisteredEventListener<>("event.name", (EventHandler<Object>) handler, (Class<Object>) inputClass);
    }

    private static Mono<Void> collect(List<Object> handled, Object received) {
        handled.add(received);
        return Mono.empty();
    }

    private Message createMessage() {
        return new Message() {
            @Override
            public String getType() { return "test"; }
            @Override
            public byte[] getBody() { return new byte[0]; }
            @Override
            public Properties getProperties() {
                return new Properties() {
                    @Override
                    public String getContentType() { return "application/json"; }
                    @Override
                    public long getContentLength() { return 0; }
                    @Override
                    public Map<String, Object> getHeaders() { return Map.of(); }
                };
            }
        };
    }
}
