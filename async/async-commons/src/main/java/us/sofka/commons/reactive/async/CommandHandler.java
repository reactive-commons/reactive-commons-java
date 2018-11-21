package us.sofka.commons.reactive.async;

import reactor.core.publisher.Mono;

public interface CommandHandler<T, C> {
    Mono<T> handle(C command);
    default Class<C> commandClass() {
        return null;
    }
}

