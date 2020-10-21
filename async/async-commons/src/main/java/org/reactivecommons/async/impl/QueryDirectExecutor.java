package org.reactivecommons.async.impl;


import org.reactivecommons.async.api.handlers.QueryExecutor;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.impl.communications.Message;
import reactor.core.publisher.Mono;

import java.util.function.Function;

public class QueryDirectExecutor<C, R> implements QueryExecutor<R, Message> {
    private final QueryHandler<R, C> queryHandler;
    private final Function<Message, C> converter;

    public QueryDirectExecutor(QueryHandler<R, C> queryHandler, Function<Message, C> converter) {
        this.queryHandler = queryHandler;
        this.converter = converter;
    }

    public Mono<R> execute(Message rawMessage) {
        return queryHandler.handle(converter.apply(rawMessage));
    }
}
