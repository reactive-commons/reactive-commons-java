package org.reactivecommons.async.impl.ext;

import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.impl.communications.Message;
import reactor.core.publisher.Mono;

public interface CustomReporter {

    String COMMAND_CLASS = "org.reactivecommons.api.domain.Command";
    String EVENT_CLASS = "org.reactivecommons.api.domain.DomainEvent";
    String QUERY_CLASS = "org.reactivecommons.async.api.AsyncQuery";

    default Mono<Void> reportError(Throwable ex, Message rawMessage, Object message, boolean redelivered) {
        switch (message.getClass().getName()){
            case COMMAND_CLASS:
                return reportError(ex, rawMessage, (Command<?>) message, redelivered);
            case EVENT_CLASS:
                return reportError(ex, rawMessage, (DomainEvent<?>) message, redelivered);
            case QUERY_CLASS:
                return reportError(ex, rawMessage, (AsyncQuery<?>) message, redelivered);
            default:
                return Mono.empty();
        }
    }

    default void reportMetric(String type, String handlerPath, Long duration, boolean success) {
    }

    Mono<Void> reportError(Throwable ex, Message rawMessage, Command<?> message, boolean redelivered);
    Mono<Void> reportError(Throwable ex, Message rawMessage, DomainEvent<?> message, boolean redelivered);
    Mono<Void> reportError(Throwable ex, Message rawMessage, AsyncQuery<?> message, boolean redelivered);

}
