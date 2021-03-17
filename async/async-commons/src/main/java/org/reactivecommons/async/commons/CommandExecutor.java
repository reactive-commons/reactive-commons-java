package org.reactivecommons.async.commons;


import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.handlers.CommandHandler;
import org.reactivecommons.async.commons.communications.Message;
import reactor.core.publisher.Mono;

import java.util.function.Function;

public class CommandExecutor<T> {
    private final CommandHandler<T> eventHandler;
    private final Function<Message, Command<T>> converter;

    public CommandExecutor(CommandHandler<T> eventHandler, Function<Message, Command<T>> converter) {
        this.eventHandler = eventHandler;
        this.converter = converter;
    }

    public Mono<Void> execute(Message rawMessage){
        return eventHandler.handle(converter.apply(rawMessage));
    }
}
