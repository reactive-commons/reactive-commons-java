package org.reactivecommons.async.commons.reply;

import org.reactivecommons.async.commons.communications.Message;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;

public class ReactiveReplyRouter {
    private final ConcurrentHashMap<String, Sinks.One<Message>> processors = new ConcurrentHashMap<>();

    public Mono<Message> register(String correlationID) {
        final Sinks.One<Message> processor = Sinks.one();
        processors.put(correlationID, processor);
        return processor.asMono();
    }

    public void routeReply(String correlationID, Message data) {
        final Sinks.One<Message> processor = processors.remove(correlationID);
        if (processor != null) {
            processor.tryEmitValue(data);
        }
    }

    public void deregister(String correlationID) {
        processors.remove(correlationID);
    }

    public void routeEmpty(String correlationID) {
        final Sinks.One<Message> processor = processors.remove(correlationID);
        if (processor != null) {
            processor.tryEmitEmpty();
        }
    }
}
