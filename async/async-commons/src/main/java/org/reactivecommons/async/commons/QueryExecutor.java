package org.reactivecommons.async.commons;


import org.reactivecommons.async.api.From;
import org.reactivecommons.async.api.handlers.QueryHandlerDelegate;
import org.reactivecommons.async.commons.communications.Message;
import reactor.core.publisher.Mono;

import java.util.function.Function;

import static org.reactivecommons.async.commons.Headers.CORRELATION_ID;
import static org.reactivecommons.async.commons.Headers.REPLY_ID;

public class QueryExecutor<T, M> {
    private final QueryHandlerDelegate<T, M> queryHandler;
    private final Function<Message, M> converter;

    public QueryExecutor(QueryHandlerDelegate<T, M> queryHandler, Function<Message, M> converter) {
        this.queryHandler = queryHandler;
        this.converter = converter;
    }

    public Mono<T> execute(Message rawMessage) {
        From from = new From();
        from.setCorrelationID(rawMessage.getProperties().getHeaders().getOrDefault(CORRELATION_ID, "").toString());
        from.setReplyID(rawMessage.getProperties().getHeaders().getOrDefault(REPLY_ID, "").toString());
        return queryHandler.handle(from, converter.apply(rawMessage));
    }
}
