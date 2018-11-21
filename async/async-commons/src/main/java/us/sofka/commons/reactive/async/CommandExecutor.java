package us.sofka.commons.reactive.async;


import reactor.core.publisher.Mono;

import java.util.function.Function;

public class CommandExecutor<C, R> {
    private final CommandHandler<R, C> commandHandler;
    private final Function<String, C> converter;

    public CommandExecutor(CommandHandler<R, C> commandHandler, Function<String, C> converter) {
        this.commandHandler = commandHandler;
        this.converter = converter;
    }

    public Mono<R> execute(String rawMessage){
        return commandHandler.handle(converter.apply(rawMessage));
    }
}
