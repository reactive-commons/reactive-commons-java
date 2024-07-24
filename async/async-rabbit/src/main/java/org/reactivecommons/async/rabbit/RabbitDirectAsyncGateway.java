package org.reactivecommons.async.rabbit;

import io.cloudevents.CloudEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.From;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import reactor.core.observability.micrometer.Micrometer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static java.lang.Boolean.TRUE;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;
import static org.reactivecommons.async.commons.Headers.*;
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
    private final MeterRegistry meterRegistry;


    public RabbitDirectAsyncGateway(BrokerConfig config, ReactiveReplyRouter router, ReactiveMessageSender sender,
                                    String exchange, MessageConverter converter, MeterRegistry meterRegistry) {
        this.config = config;
        this.router = router;
        this.sender = sender;
        this.exchange = exchange;
        this.converter = converter;
        this.persistentCommands = config.isPersistentCommands();
        this.persistentQueries = config.isPersistentQueries();
        this.replyTimeout = config.getReplyTimeout();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName) {
        return sendCommand(command, targetName, 0, DEFAULT_DOMAIN);
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName, long delayMillis) {
        return sendCommand(command, targetName, delayMillis, DEFAULT_DOMAIN);
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName, String domain) {
        return sendCommand(command, targetName, 0, domain);
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName, long delayMillis, String domain) {
        Tuple2<String, Map<String, Object>> targetAndHeaders = validateDelay(targetName, delayMillis);
        return resolveSender(domain).sendWithConfirm(command, exchange, targetAndHeaders.getT1(),
                targetAndHeaders.getT2(), persistentCommands);
    }

    @Override
    public Mono<Void> sendCloudCommand(CloudEvent command, String targetName) {
        return sendCloudCommand(command, targetName, 0, DEFAULT_DOMAIN);
    }

    @Override
    public Mono<Void> sendCloudCommand(CloudEvent command, String targetName, String domain) {
        return sendCloudCommand(command, targetName, 0, domain);
    }

    @Override
    public Mono<Void> sendCloudCommand(CloudEvent command, String targetName, long delayMillis, String domain) {
        Tuple2<String, Map<String, Object>> targetAndHeaders = validateDelay(targetName, delayMillis);
        return resolveSender(domain).sendWithConfirm(command, exchange, targetAndHeaders.getT1(),
                targetAndHeaders.getT2(), persistentCommands);
    }

    public <T> Flux<OutboundMessageResult> sendCommands(Flux<Command<T>> commands, String targetName) {
        return sender.sendWithConfirmBatch(commands, exchange, targetName, Collections.emptyMap(), persistentCommands);
    }


    @Override
    public <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type) {
        return requestReply(query, targetName, type, DEFAULT_DOMAIN);
    }

    @Override
    public <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type, String domain) {
        final String correlationID = UUID.randomUUID().toString().replaceAll("-", "");

        final Mono<R> replyHolder = router.register(correlationID)
                .timeout(replyTimeout)
                .doOnError(TimeoutException.class, e -> router.deregister(correlationID))
                .flatMap(s -> fromCallable(() -> converter.readValue(s, type)));

        Map<String, Object> headers = new HashMap<>();
        headers.put(REPLY_ID, config.getRoutingKey());
        headers.put(SERVED_QUERY_ID, query.getResource());
        headers.put(CORRELATION_ID, correlationID);
        headers.put(REPLY_TIMEOUT_MILLIS, replyTimeout.toMillis());

        return resolveSender(domain).sendNoConfirm(query, exchange, targetName + ".query", headers, persistentQueries)
                .then(replyHolder)
                .name("async_query")
                .tag("operation", query.getResource())
                .tag("target", targetName)
                .tap(Micrometer.metrics(meterRegistry));
    }

    @Override
    public <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type) {
        return requestReply(new AsyncQuery<>(query.getType(), query), targetName, type);
    }

    @Override
    public <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type, String domain) {
        return requestReply(new AsyncQuery<>(query.getType(), query), targetName, type, domain);
    }

    @Override
    public <T> Mono<Void> reply(T response, From from) {
        final HashMap<String, Object> headers = new HashMap<>();
        headers.put(CORRELATION_ID, from.getCorrelationID());

        if (response == null) {
            headers.put(COMPLETION_ONLY_SIGNAL, TRUE.toString());
        }

        return sender.sendNoConfirm(response, "globalReply", from.getReplyID(), headers, false);
    }

    protected ReactiveMessageSender resolveSender(String domain) { // NOSONAR
        return sender;
    }

    private Tuple2<String, Map<String, Object>> validateDelay(String targetName, long delayMillis) {
        Map<String, Object> headers = new HashMap<>();
        String realTarget = targetName;
        if (delayMillis > 0) {
            headers.put(DELAYED, String.valueOf(delayMillis));
            realTarget = targetName + "-delayed";
        }
        return Tuples.of(realTarget, headers);
    }

}
