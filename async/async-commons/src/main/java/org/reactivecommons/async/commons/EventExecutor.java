package org.reactivecommons.async.commons;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.handlers.CloudEventHandler;
import org.reactivecommons.async.api.handlers.DomainEventHandler;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.RawEventHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import reactor.core.publisher.Mono;

import java.util.function.Function;

@RequiredArgsConstructor
public class EventExecutor<T> {
    private final EventHandler<T> eventHandler;
    private final Function<Message, T> converter;

    /**
     * The executor of the handler of the listener, which reads each message as the handler expects it: as a domain
     * event of the class of the listener, as a cloud event, or as it was received for a raw handler.
     */
    public static EventExecutor<Object> of(RegisteredEventListener<Object, Object> listener,
                                           MessageConverter converter) {
        return new EventExecutor<>(listener.handler(), reader(listener, converter));
    }

    private static <T, D> Function<Message, Object> reader(RegisteredEventListener<T, D> listener,
                                                          MessageConverter converter) {
        if (listener.handler() instanceof DomainEventHandler) {
            final Class<T> eventClass = listener.inputClass();
            return message -> converter.readDomainEvent(message, eventClass);
        }
        if (listener.handler() instanceof CloudEventHandler) {
            return converter::readCloudEvent;
        }
        if (listener.handler() instanceof RawEventHandler) {
            return message -> message;
        }
        throw new IllegalArgumentException("Unknown handler type");
    }

    public Mono<Void> execute(Message rawMessage) {
        return eventHandler.handle(converter.apply(rawMessage));
    }
}
