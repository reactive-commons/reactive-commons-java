package org.reactivecommons.async.impl;


import org.reactivecommons.async.api.From;
import org.reactivecommons.async.api.handlers.QueryExecutor;
import org.reactivecommons.async.api.handlers.QueryHandlerDelegate;
import org.reactivecommons.async.impl.communications.Message;
import reactor.core.publisher.Mono;

import java.util.function.Function;

import static org.reactivecommons.async.impl.Headers.CORRELATION_ID;
import static org.reactivecommons.async.impl.Headers.REPLY_ID;

public class QueryDelegateExecutor<M> implements QueryExecutor<Void, Message> {
    private final QueryHandlerDelegate<M> queryHandler;
    private final Function<Message, M> converter;

    public QueryDelegateExecutor(QueryHandlerDelegate<M> queryHandler, Function<Message, M> converter) {
        this.queryHandler = queryHandler;
        this.converter = converter;
    }

    public Mono<Void> execute(Message rawMessage) {
        From from = new From();
        from.setCorrelationID(rawMessage.getProperties().getHeaders().get(CORRELATION_ID).toString());
        from.setReplyID(rawMessage.getProperties().getHeaders().get(REPLY_ID).toString());
        return queryHandler.handle(from, converter.apply(rawMessage));
    }
}
