package org.reactivecommons.async.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.config.BrokerConfig;
import org.reactivecommons.async.impl.reply.ReactiveReplyRouter;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static reactor.core.publisher.Mono.fromCallable;

public class RabbitDirectAsyncGateway implements DirectAsyncGateway {

    private final ObjectMapper mapper = new ObjectMapper();

    private final BrokerConfig config;
    private final ReactiveReplyRouter router;
    private final ReactiveMessageSender sender;
    private final String exchange;


    public RabbitDirectAsyncGateway(BrokerConfig config, ReactiveReplyRouter router, ReactiveMessageSender sender, String exchange) {
        this.config = config;
        this.router = router;
        this.sender = sender;
        this.exchange = exchange;
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName) {
        return sender.sendWithConfirm(command, exchange, targetName, Collections.emptyMap());
    }

    @Override
    public <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type) {
        final String correlationID = UUID.randomUUID().toString().replaceAll("-", "");

        final Mono<R> replyHolder = router.register(correlationID).timeout(Duration.ofSeconds(15)).flatMap(s ->
                fromCallable(() -> String.class.equals(type) ? type.cast(s) : mapper.readValue(s, type)));

        Map<String, Object> headers = new HashMap<>();
        headers.put("x-reply_id", config.getRoutingKey());
        headers.put("x-serveQuery-id", query.getResource());
        headers.put("x-correlation-id", correlationID);

        return sender.sendWithConfirm(query, exchange, targetName+".query", headers).then(replyHolder);
    }

}
