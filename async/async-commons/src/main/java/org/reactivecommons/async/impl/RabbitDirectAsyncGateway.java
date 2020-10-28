package org.reactivecommons.async.impl;

import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.From;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.config.BrokerConfig;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.impl.reply.ReactiveReplyRouter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessageResult;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static java.lang.Boolean.TRUE;
import static org.reactivecommons.async.impl.Headers.*;
import static reactor.core.publisher.Mono.fromCallable;

public class RabbitDirectAsyncGateway implements DirectAsyncGateway {

    private final BrokerConfig config;
    private final ReactiveReplyRouter router;
    private final ReactiveMessageSender sender;
    private final String exchange;
    private final MessageConverter converter;
    private final boolean persistentCommands;
    private final boolean persistentQueries;
    private final Duration replyTimeout;


    public RabbitDirectAsyncGateway(BrokerConfig config, ReactiveReplyRouter router, ReactiveMessageSender sender,
                                    String exchange, MessageConverter converter) {
        this.config = config;
        this.router = router;
        this.sender = sender;
        this.exchange = exchange;
        this.converter = converter;
        this.persistentCommands = config.isPersistentCommands();
        this.persistentQueries = config.isPersistentQueries();
        this.replyTimeout = config.getReplyTimeout();
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName) {
        return sender.sendWithConfirm(command, exchange, targetName, Collections.emptyMap(), persistentCommands);
    }

    public <T> Flux<OutboundMessageResult> sendCommands(Flux<Command<T>> commands, String targetName) {
        return sender.sendWithConfirmBatch(commands, exchange, targetName, Collections.emptyMap(), persistentCommands);
    }


    @Override
    public <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type) {
        final String correlationID = UUID.randomUUID().toString().replaceAll("-", "");

        final Mono<R> replyHolder = router.register(correlationID)
                .timeout(replyTimeout)
                .flatMap(s -> fromCallable(() -> converter.readValue(s, type)));

        Map<String, Object> headers = new HashMap<>();
        headers.put(REPLY_ID, config.getRoutingKey());
        headers.put(SERVED_QUERY_ID, query.getResource());
        headers.put(CORRELATION_ID, correlationID);

        return sender.sendNoConfirm(query, exchange, targetName + ".query", headers, persistentQueries).then(replyHolder);
    }

    @Override
    public <T> Mono<Void> reply(T response, From from) {
        final HashMap<String, Object> headers = new HashMap<>();
        headers.put(CORRELATION_ID, from.getCorrelationID());

        if (response == null) {
            headers.put(Headers.COMPLETION_ONLY_SIGNAL, TRUE.toString());
        }

        return sender.sendNoConfirm(response, "globalReply", from.getReplyID(), headers, false);
    }

}
