package org.reactivecommons.async.commons.reply;

import org.reactivecommons.async.commons.communications.Message;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes each query reply to the caller waiting for it, by correlation ID. A single router is shared by every domain,
 * so each pending reply remembers the domain whose reply queue it is expected on.
 */
public class ReactiveReplyRouter {
    private final ConcurrentHashMap<String, PendingReply> processors = new ConcurrentHashMap<>();

    private record PendingReply(Sinks.One<Message> sink, String domain) {
    }

    public Mono<Message> register(String correlationID) {
        return register(correlationID, null);
    }

    /**
     * @param correlationID the correlation ID of the query
     * @param domain        the domain whose reply queue the reply is expected on, or {@code null} if unknown
     * @return the reply, once routed
     */
    public Mono<Message> register(String correlationID, String domain) {
        final Sinks.One<Message> processor = Sinks.one();
        processors.put(correlationID, new PendingReply(processor, domain));
        return processor.asMono();
    }

    public void routeReply(String correlationID, Message data) {
        final PendingReply pending = processors.remove(correlationID);
        if (pending != null) {
            pending.sink().tryEmitValue(data);
        }
    }

    public void deregister(String correlationID) {
        processors.remove(correlationID);
    }

    public void routeEmpty(String correlationID) {
        final PendingReply pending = processors.remove(correlationID);
        if (pending != null) {
            pending.sink().tryEmitEmpty();
        }
    }

    /**
     * Fails every pending correlation with the given cause.
     *
     * @param cause the failure to emit on every pending sink
     * @return the number of correlations that were pending
     * @see #failAll(String, Throwable)
     */
    public int failAll(Throwable cause) {
        return failAll(null, cause);
    }

    /**
     * Fails the pending correlations of a domain with the given cause. Used by {@code ApplicationReplyListener} when
     * its reply queue is lost: the queue is exclusive + autoDelete, so it vanishes with its connection and any reply
     * routed to it meanwhile is dropped. Callers see the failure immediately instead of waiting for the reply
     * timeout. The correlations of the other domains are not affected: their reply queues are fine.
     *
     * @param domain the domain whose reply queue was lost, or {@code null} to fail the correlations of every domain
     * @param cause  the failure to emit on every pending sink
     * @return the number of correlations that were failed
     */
    public int failAll(String domain, Throwable cause) {
        int count = 0;
        for (var iterator = processors.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (domain != null && !Objects.equals(domain, entry.getValue().domain())) {
                continue;
            }
            iterator.remove();
            if (entry.getValue().sink().tryEmitError(cause).isSuccess()) {
                count++;
            }
        }
        return count;
    }
}
