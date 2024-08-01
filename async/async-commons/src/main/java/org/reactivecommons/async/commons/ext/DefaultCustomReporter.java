package org.reactivecommons.async.commons.ext;

import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.commons.communications.Message;
import reactor.core.publisher.Mono;

public class DefaultCustomReporter implements CustomReporter {

    @Override
    public Mono<Void> reportError(Throwable ex, Message rawMessage, Command<?> message, boolean redelivered) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> reportError(Throwable ex, Message rawMessage, DomainEvent<?> message, boolean redelivered) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> reportError(Throwable ex, Message rawMessage, AsyncQuery<?> message, boolean redelivered) {
        return Mono.empty();
    }
}
