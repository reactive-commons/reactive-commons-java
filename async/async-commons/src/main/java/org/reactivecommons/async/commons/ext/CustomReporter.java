package org.reactivecommons.async.commons.ext;

import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.commons.communications.Message;
import reactor.core.publisher.Mono;

public interface CustomReporter {

    String COMMAND_CLASS = "org.reactivecommons.api.domain.Command";
    String EVENT_CLASS = "org.reactivecommons.api.domain.DomainEvent";
    String QUERY_CLASS = "org.reactivecommons.async.api.AsyncQuery";

    default Mono<Void> reportError(Throwable ex, Message rawMessage, Object message, boolean redelivered) {
        var name = message.getClass().getName();
        return Mono.just(name)
                .filter(COMMAND_CLASS::equals)
                .flatMap(n -> reportError(ex, rawMessage, (Command<?>) message, redelivered))
                .switchIfEmpty(Mono.just(name)
                        .filter(EVENT_CLASS::equals)
                        .flatMap(n -> reportError(ex, rawMessage, (DomainEvent<?>) message, redelivered))
                        .switchIfEmpty(Mono.just(name)
                                .filter(QUERY_CLASS::equals)
                                .flatMap(n -> reportError(ex, rawMessage, (AsyncQuery<?>) message, redelivered))
                        )
                );
    }

    default void reportMetric(String type, String handlerPath, Long duration, boolean success) {
    }

    Mono<Void> reportError(Throwable ex, Message rawMessage, Command<?> message, boolean redelivered);

    Mono<Void> reportError(Throwable ex, Message rawMessage, DomainEvent<?> message, boolean redelivered);

    Mono<Void> reportError(Throwable ex, Message rawMessage, AsyncQuery<?> message, boolean redelivered);

}
