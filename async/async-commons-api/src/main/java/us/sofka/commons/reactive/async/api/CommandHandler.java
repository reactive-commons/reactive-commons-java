package us.sofka.commons.reactive.async.api;

import reactor.core.publisher.Mono;

public interface CommandHandler<C> {
    Mono<Void> handle(C command);
    Class<C> commandClass();
}

