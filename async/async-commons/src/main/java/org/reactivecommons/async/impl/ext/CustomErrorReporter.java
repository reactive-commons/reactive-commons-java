package org.reactivecommons.async.impl.ext;

import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.impl.communications.Message;
import reactor.core.publisher.Mono;

public interface CustomErrorReporter {

    String COMMAND_CLASS = "org.reactivecommons.api.domain.Command";
    String EVENT_CLASS = "org.reactivecommons.api.domain.DomainEvent";
    String QUERY_CLASS = "org.reactivecommons.async.api.AsyncQuery";

    default Mono<Void> reportError(Throwable ex, Message rawMessage, Object message) {
        switch (message.getClass().getName()){
            case COMMAND_CLASS:
                return reportError(ex, rawMessage, (Command<?>) message);
            case EVENT_CLASS:
                return reportError(ex, rawMessage, (DomainEvent<?>) message);
            case QUERY_CLASS:
                return reportError(ex, rawMessage, (AsyncQuery<?>) message);
            default:
                return Mono.empty();
        }
    }

    Mono<Void> reportError(Throwable ex, Message rawMessage, Command<?> message);
    Mono<Void> reportError(Throwable ex, Message rawMessage, DomainEvent<?> message);
    Mono<Void> reportError(Throwable ex, Message rawMessage, AsyncQuery<?> message);

}
