package org.reactivecommons.async.commons;

import lombok.AllArgsConstructor;
import org.reactivecommons.async.api.handlers.CommandHandler;
import org.reactivecommons.async.commons.communications.Message;
import reactor.core.publisher.Mono;

import java.util.function.Function;

@AllArgsConstructor
public class CommandExecutor<T> {
    private final CommandHandler<T> eventHandler;
    private final Function<Message, T> converter;

    public Mono<Void> execute(Message rawMessage) {
        return eventHandler.handle(converter.apply(rawMessage));
    }
}
