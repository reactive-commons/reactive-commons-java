package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import io.cloudevents.CloudEvent;
import lombok.extern.java.Log;
import org.reactivecommons.async.commons.exceptions.MissingHandlerException;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.QueryExecutor;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;

import static java.util.Optional.ofNullable;
import static org.reactivecommons.async.commons.Headers.CORRELATION_ID;
import static org.reactivecommons.async.commons.Headers.REPLY_DEADLINE_MILLIS;
import static org.reactivecommons.async.commons.Headers.REPLY_ID;
import static org.reactivecommons.async.commons.Headers.REPLY_TIMEOUT_MILLIS;
import static org.reactivecommons.async.commons.Headers.SERVED_QUERY_ID;

@Log
public class ApplicationQueryListener extends GenericMessageListener {
    private final MessageConverter converter;
    private final HandlerResolver handlerResolver;
    private final ReactiveMessageSender sender;
    private final String replyExchange;
    private final String directExchange;
    private final boolean withDLQRetry;
    private final int retryDelay;
    private final Optional<Integer> maxLengthBytes;
    private final boolean discardTimeoutQueries;


    public ApplicationQueryListener(ReactiveMessageListener listener, String queueName, HandlerResolver resolver,
                                    ReactiveMessageSender sender, String directExchange, MessageConverter converter,
                                    String replyExchange, boolean withDLQRetry, boolean createTopology, long maxRetries,
                                    int retryDelay, Optional<Integer> maxLengthBytes, boolean discardTimeoutQueries,
                                    DiscardNotifier discardNotifier, CustomReporter errorReporter) {
        super(queueName, listener, withDLQRetry, createTopology, maxRetries, retryDelay, discardNotifier,
                "query", errorReporter);
        this.retryDelay = retryDelay;
        this.withDLQRetry = withDLQRetry;
        this.converter = converter;
        this.handlerResolver = resolver;
        this.sender = sender;
        this.replyExchange = replyExchange;
        this.directExchange = directExchange;
        this.maxLengthBytes = maxLengthBytes;
        this.discardTimeoutQueries = discardTimeoutQueries;
    }

    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        final RegisteredQueryHandler<Object, Object> handler = handlerResolver.getQueryHandler(executorPath);
        if (handler == null) {
            throw new MissingHandlerException("query", executorPath);
        }
        Function<Message, Object> messageConverter = resolveConverter(handler.queryClass());
        final QueryExecutor<Object, Object> executor = new QueryExecutor<>(handler.handler(), messageConverter);
        return executor::execute;
    }

    @Override
    protected String handlerName(String executorPath) {
        final RegisteredQueryHandler<Object, Object> handler = handlerResolver.getQueryHandler(executorPath);
        return handler != null ? handler.path() : null;
    }

    private Function<Message, Object> resolveConverter(Class<?> handlerClass) {
        if (handlerClass == CloudEvent.class) {
            return converter::readCloudEvent;
        }
        return msj -> converter.readAsyncQuery(msj, handlerClass).getQueryData();
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        final Mono<AMQP.Exchange.DeclareOk> declareExchange = creator.declare(
                ExchangeSpecification.exchange(directExchange).durable(true).type("direct")
        );
        if (withDLQRetry) {
            final Mono<AMQP.Exchange.DeclareOk> declareExchangeDLQ = creator.declare(
                    ExchangeSpecification.exchange(directExchange + ".DLQ").durable(true).type("direct")
            );
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declareQueue(
                    queueName, directExchange + ".DLQ", maxLengthBytes
            );
            final Mono<AMQP.Queue.DeclareOk> declareDLQ = creator.declareDLQ(
                    queueName, directExchange, retryDelay, maxLengthBytes
            );
            final Mono<AMQP.Queue.BindOk> binding = creator.bind(
                    BindingSpecification.binding(directExchange, queueName, queueName)
            );
            final Mono<AMQP.Queue.BindOk> bindingDLQ = creator.bind(
                    BindingSpecification.binding(directExchange + ".DLQ", queueName, queueName + ".DLQ")
            );
            return declareExchange
                    .then(declareExchangeDLQ)
                    .then(declareQueue)
                    .then(declareDLQ)
                    .then(binding)
                    .then(bindingDLQ)
                    .then();
        } else {
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declareQueue(queueName, maxLengthBytes);
            final Mono<AMQP.Queue.BindOk> binding = creator.bind(
                    BindingSpecification.binding(directExchange, queueName, queueName));
            return declareExchange.then(declareQueue).then(binding).then();
        }
    }

    @Override
    protected Mono<AcknowledgableDelivery> handle(AcknowledgableDelivery msj, long initNanos) {
        AMQP.BasicProperties messageProperties = msj.getProperties();
        if (!canBeAnswered(messageProperties)) {
            // e.g. published by another client: running the handler is pointless, nobody can get its reply
            return Mono.error(new IllegalArgumentException("Query without the " + SERVED_QUERY_ID + ", " + REPLY_ID
                    + " and " + CORRELATION_ID + " headers needed to serve it and reply to it"));
        }

        if (!hasTimeoutMetadata(messageProperties) || !discardTimeoutQueries) {
            return super.handle(msj, initNanos);
        }

        // Deferred: a failure computing the timeout must fail this message only, never the whole listener flux
        return Mono.defer(() -> replyDeadlineMillis(messageProperties)
                .map(deadline -> handleWithDeadline(msj, initNanos, deadline))
                .orElseGet(() -> {
                    // counted here only: retryIsUseless parses the same header again when the query fails
                    metrics.recordInvalidTimeoutHeader();
                    return super.handle(msj, initNanos);
                }));
    }

    private static boolean hasTimeoutMetadata(AMQP.BasicProperties properties) {
        Map<String, Object> headers = properties.getHeaders();
        return headers.containsKey(REPLY_DEADLINE_MILLIS)
                || (properties.getTimestamp() != null && headers.containsKey(REPLY_TIMEOUT_MILLIS));
    }

    /**
     * When the caller stops waiting for the reply, in milliseconds since the epoch. The gateway publishes it in the
     * {@code x-reply-deadline-millis} header; for queries published without it, it is computed from the AMQP
     * {@code timestamp} (whose resolution is one second: the deadline can be up to a second early) plus the reply
     * timeout. Empty when the query carries no (valid) timeout metadata.
     */
    private Optional<Long> replyDeadlineMillis(AMQP.BasicProperties properties) {
        Map<String, Object> headers = properties.getHeaders();
        if (headers.containsKey(REPLY_DEADLINE_MILLIS)) {
            return millisHeader(REPLY_DEADLINE_MILLIS, headers.get(REPLY_DEADLINE_MILLIS));
        }
        if (properties.getTimestamp() == null || !headers.containsKey(REPLY_TIMEOUT_MILLIS)) {
            return Optional.empty();
        }
        return millisHeader(REPLY_TIMEOUT_MILLIS, headers.get(REPLY_TIMEOUT_MILLIS))
                .map(timeout -> properties.getTimestamp().getTime() + timeout);
    }

    /**
     * The gateway publishes the headers as {@code long}s, which the RabbitMQ client decodes as {@link Long}s; other
     * publishers may send an {@link Integer} or a string.
     */
    private Optional<Long> millisHeader(String name, Object header) {
        if (header instanceof Number number) {
            return Optional.of(number.longValue());
        }
        try {
            return Optional.of(Long.parseLong(String.valueOf(header).trim()));
        } catch (NumberFormatException e) {
            log.log(Level.WARNING, () -> "Ignoring invalid " + name + " header: " + header);
            return Optional.empty();
        }
    }

    private Mono<AcknowledgableDelivery> handleWithDeadline(AcknowledgableDelivery msj, long initNanos,
                                                            long replyDeadlineMillis) {
        long millisUntilTimeout = replyDeadlineMillis - currentTimestamp().toEpochMilli();
        String executorPath = getExecutorPath(msj);

        if (millisUntilTimeout > 0) {
            return super.handle(msj, initNanos)
                    .timeout(Duration.ofMillis(millisUntilTimeout), buildTimeOutFallback(executorPath));
        }

        return buildTimeOutFallback(executorPath);
    }

    private static boolean canBeAnswered(AMQP.BasicProperties properties) {
        Map<String, Object> headers = properties.getHeaders();
        return headers != null && headers.get(SERVED_QUERY_ID) != null && headers.get(REPLY_ID) != null
                && headers.get(CORRELATION_ID) != null;
    }

    private Instant currentTimestamp() {
        return Instant.now();
    }

    /**
     * A failed query is not retried once its caller stopped waiting for the reply (its publication time plus the
     * reply timeout it carries): nobody would receive the reply.
     */
    @Override
    protected boolean retryIsUseless(AcknowledgableDelivery msj) {
        AMQP.BasicProperties properties = msj.getProperties();
        if (!canBeAnswered(properties)) {
            return true;
        }
        return replyDeadlineMillis(properties)
                .map(deadline -> deadline <= currentTimestamp().toEpochMilli())
                .orElse(false);
    }

    private Mono<AcknowledgableDelivery> buildTimeOutFallback(String executorPath) {
        return Mono.fromRunnable(() -> {
            metrics.recordQueryDiscardedByTimeout();
            log.log(Level.WARNING, () -> String.format("query with path %s discarded by timeout", executorPath));
        });
    }

    @Override
    protected String getExecutorPath(AcknowledgableDelivery msj) {
        return msj.getProperties().getHeaders().get(SERVED_QUERY_ID).toString();
    }

    @Override
    protected Function<Mono<Object>, Mono<Object>> enrichPostProcess(Message msg) {
        return m -> m.materialize().flatMap(signal -> {
            if (signal.isOnError()) {
                return Mono.error(ofNullable(signal.getThrowable()).orElseGet(RuntimeException::new));
            }
            if (signal.isOnComplete()) {
                return Mono.empty();
            }

            final String replyID = msg.getProperties().getHeaders().get(REPLY_ID).toString();
            final String correlationID = msg.getProperties().getHeaders().get(CORRELATION_ID).toString();
            final HashMap<String, Object> headers = new HashMap<>();
            headers.put(CORRELATION_ID, correlationID);
            Object response = signal.get();
            // the query was handled: failing to publish its reply must not make it run again
            return sender.sendNoConfirm(response, replyExchange, replyID, headers, false)
                    .onErrorResume(replyError -> {
                        metrics.recordQueryReplyFailed();
                        log.log(Level.WARNING, replyError, () -> "Reply to query " + correlationID
                                + " could not be published, its caller will not receive it");
                        return Mono.empty();
                    });
        });
    }

    @Override
    protected Object parseMessageForReporter(Message msj) {
        return converter.readAsyncQueryStructure(msj);
    }

    @Override
    protected String getKind() {
        return "queries";
    }
}


