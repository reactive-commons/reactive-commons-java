package org.reactivecommons.async.commons;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class DLQDiscardNotifierTest {

    DomainEventBus eventBus;
    MessageConverter messageConverter;
    DLQDiscardNotifier notifier;

    @BeforeEach
    void setUp() {
        eventBus = mock(DomainEventBus.class);
        messageConverter = mock(MessageConverter.class);
        notifier = new DLQDiscardNotifier(eventBus, messageConverter);
        when(eventBus.emit(any(DomainEvent.class))).thenReturn(Mono.empty());
        when(eventBus.emit(any(CloudEvent.class))).thenReturn(Mono.empty());
    }

    @Test
    void notifyDiscardWithCloudEvent() {
        var message = createMessage("body".getBytes(), "application/cloudevents+json");
        var cloudEvent = CloudEventBuilder.v1()
                .withId("123")
                .withType("test.type")
                .withSource(URI.create("/test"))
                .build();
        when(messageConverter.readCloudEvent(message)).thenReturn(cloudEvent);

        notifier.notifyDiscard(message).block();

        verify(eventBus).emit(argThat((CloudEvent ce) -> ce.getType().equals("test.type.dlq")));
    }

    @Test
    void notifyDiscardWithCommandJson() {
        var message = createMessage(
                "{\"name\":\"myCmd\",\"commandId\":\"cid\",\"data\":{}}".getBytes(),
                "application/json");

        // The notifier reads the message as JsonSkeleton via messageConverter.readValue
        // We mock readValue to throw for CloudEvent check (not cloud event), then succeed for skeleton
        when(messageConverter.readCloudEvent(message)).thenThrow(new RuntimeException("not a CE"));

        // Since message content-type is not cloud event, it reads JsonSkeleton
        // We need to mock readValue for the private JsonSkeleton class
        // Instead, use doAnswer to return a command-like object
        doAnswer(inv -> {
            Class<?> cls = inv.getArgument(1);
            var mapper = new tools.jackson.databind.json.JsonMapper();
            return mapper.readValue(message.getBody(), cls);
        }).when(messageConverter).readValue(eq(message), any(Class.class));

        notifier.notifyDiscard(message).block();

        verify(eventBus).emit(argThat((DomainEvent<?> ev) -> ev.getName().equals("myCmd.dlq")));
    }

    @Test
    void notifyDiscardWithEventJson() {
        var message = createMessage(
                "{\"name\":\"myEvt\",\"eventId\":\"eid\",\"data\":{}}".getBytes(),
                "application/json");

        doAnswer(inv -> {
            Class<?> cls = inv.getArgument(1);
            var mapper = new tools.jackson.databind.json.JsonMapper();
            return mapper.readValue(message.getBody(), cls);
        }).when(messageConverter).readValue(eq(message), any(Class.class));

        notifier.notifyDiscard(message).block();

        verify(eventBus).emit(argThat((DomainEvent<?> ev) -> ev.getName().equals("myEvt.dlq")));
    }

    @Test
    void notifyDiscardWithQueryJson() {
        var message = createMessage(
                "{\"resource\":\"query.test\",\"queryData\":{}}".getBytes(),
                "application/json");

        doAnswer(inv -> {
            Class<?> cls = inv.getArgument(1);
            var mapper = new tools.jackson.databind.json.JsonMapper();
            return mapper.readValue(message.getBody(), cls);
        }).when(messageConverter).readValue(eq(message), any(Class.class));

        notifier.notifyDiscard(message).block();

        verify(eventBus).emit(argThat((DomainEvent<?> ev) -> ev.getName().equals("query.test.dlq")));
    }

    @Test
    void notifyDiscardWithUnreadableMessage() {
        var message = createMessage("garbage".getBytes(), "application/json");

        when(messageConverter.readValue(eq(message), any(Class.class)))
                .thenThrow(new MessageConversionException("cannot read"));

        notifier.notifyDiscard(message).block();

        verify(eventBus).emit(argThat((DomainEvent<?> ev) -> ev.getName().equals("corruptData.dlq")));
    }

    @Test
    void notifyDiscardHandlesEmitError() {
        var message = createMessage("body".getBytes(), "application/json");
        when(messageConverter.readValue(eq(message), any(Class.class)))
                .thenThrow(new MessageConversionException("bad"));
        when(eventBus.emit(any(DomainEvent.class))).thenReturn(Mono.error(new RuntimeException("bus error")));

        // Should not throw, returns empty
        var result = notifier.notifyDiscard(message).block();
        assertThat(result).isNull();
    }

    private Message createMessage(byte[] body, String contentType) {
        return new Message() {
            @Override
            public String getType() {
                return "test";
            }

            @Override
            public byte[] getBody() {
                return body;
            }

            @Override
            public Properties getProperties() {
                return new Properties() {
                    @Override
                    public String getContentType() {
                        return contentType;
                    }

                    @Override
                    public long getContentLength() {
                        return body.length;
                    }

                    @Override
                    public Map<String, Object> getHeaders() {
                        return Map.of();
                    }
                };
            }
        };
    }
}
