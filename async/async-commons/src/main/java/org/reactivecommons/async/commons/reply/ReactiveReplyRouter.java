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

    /**
     * Drains every pending correlation and fails each with the given cause. Used by {@code ApplicationReplyListener}
     * when its channel or connection is being recovered: the exclusive + autoDelete reply queue vanishes with the
     * old connection, so any reply the broker had buffered for it is lost on the new connection. Callers see the
     * failure immediately instead of waiting for {@code sendTimeout}.
     *
     * @param cause the failure to emit on every pending sink
     * @return the number of correlations that were pending
     */
    public int failAll(Throwable cause) {
        int count = 0;
        for (var iterator = processors.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            iterator.remove();
            if (entry.getValue().tryEmitError(cause).isSuccess()) {
                count++;
            }
        }
        return count;
    }
}
