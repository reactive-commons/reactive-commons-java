package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import lombok.extern.java.Log;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.rabbit.HandlerResolver;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.QueryExecutor;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;

import static java.lang.Boolean.TRUE;
import static java.util.Optional.ofNullable;
import static org.reactivecommons.async.commons.Headers.*;

@Log
//TODO: Organizar inferencia de tipos de la misma forma que en comandos y eventos
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
                                    String replyExchange, boolean withDLQRetry, long maxRetries, int retryDelay,
                                    Optional<Integer> maxLengthBytes, boolean discardTimeoutQueries,
                                    DiscardNotifier discardNotifier, CustomReporter errorReporter) {
        super(queueName, listener, withDLQRetry, maxRetries, discardNotifier, "query", errorReporter);
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
            return message -> Mono.error(new RuntimeException("Handler Not registered for Query: " + executorPath));
        }
        final Class<?> handlerClass = handler.getQueryClass();
        Function<Message, Object> messageConverter = msj -> converter.readAsyncQuery(msj, handlerClass).getQueryData();
        final QueryExecutor<Object, Object> executor = new QueryExecutor<>(handler.getHandler(), messageConverter);
        return executor::execute;
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        final Mono<AMQP.Exchange.DeclareOk> declareExchange = creator.declare(ExchangeSpecification.exchange(directExchange).durable(true).type("direct"));
        if (withDLQRetry) {
            final Mono<AMQP.Exchange.DeclareOk> declareExchangeDLQ = creator.declare(ExchangeSpecification.exchange(directExchange + ".DLQ").durable(true).type("direct"));
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declareQueue(queueName, directExchange + ".DLQ", maxLengthBytes);
            final Mono<AMQP.Queue.DeclareOk> declareDLQ = creator.declareDLQ(queueName, directExchange, retryDelay, maxLengthBytes);
            final Mono<AMQP.Queue.BindOk> binding = creator.bind(BindingSpecification.binding(directExchange, queueName, queueName));
            final Mono<AMQP.Queue.BindOk> bindingDLQ = creator.bind(BindingSpecification.binding(directExchange + ".DLQ", queueName, queueName + ".DLQ"));
            return declareExchange.then(declareExchangeDLQ).then(declareQueue).then(declareDLQ).then(binding).then(bindingDLQ).then();
        } else {
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declareQueue(queueName, maxLengthBytes);
            final Mono<AMQP.Queue.BindOk> binding = creator.bind(BindingSpecification.binding(directExchange, queueName, queueName));
            return declareExchange.then(declareQueue).then(binding).then();
        }
    }

    @Override
    protected Mono<AcknowledgableDelivery> handle(AcknowledgableDelivery msj, Instant initTime) {
        AMQP.BasicProperties messageProperties = msj.getProperties();

        boolean messageDoesNotContainTimeoutMetadata = messageProperties.getTimestamp() == null ||
                !messageProperties.getHeaders().containsKey(REPLY_TIMEOUT_MILLIS);

        if (messageDoesNotContainTimeoutMetadata || !discardTimeoutQueries) {
            return super.handle(msj, initTime);
        }

        return handleWithTimeout(msj, initTime, messageProperties);
    }

    private Mono<AcknowledgableDelivery> handleWithTimeout(AcknowledgableDelivery msj,
                                                           Instant initTime,
                                                           AMQP.BasicProperties messageProperties) {
        long messageTimestamp = msj.getProperties().getTimestamp().getTime();
        long replyTimeoutMillis = (int) messageProperties.getHeaders().get(REPLY_TIMEOUT_MILLIS);
        long millisUntilTimeout = (messageTimestamp + replyTimeoutMillis) - currentTimestamp().toEpochMilli();
        String executorPath = getExecutorPath(msj);

        if (millisUntilTimeout > 0) {
            return super.handle(msj, initTime)
                    .timeout(Duration.ofMillis(millisUntilTimeout), buildTimeOutFallback(executorPath));
        }

        return buildTimeOutFallback(executorPath);
    }

    private Instant currentTimestamp() {
        return Instant.now();
    }

    private Mono<AcknowledgableDelivery> buildTimeOutFallback(String executorPath) {
        return Mono.fromRunnable(() -> log.log(Level.WARNING, String.format("query with path %s discarded by timeout",
                executorPath)));
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

            final String replyID = msg.getProperties().getHeaders().get(REPLY_ID).toString();
            final String correlationID = msg.getProperties().getHeaders().get(CORRELATION_ID).toString();
            final HashMap<String, Object> headers = new HashMap<>();
            headers.put(CORRELATION_ID, correlationID);

            if (!signal.hasValue()) {
                headers.put(COMPLETION_ONLY_SIGNAL, TRUE.toString());
            }

            return sender.sendNoConfirm(signal.get(), replyExchange, replyID, headers, false);
        });
    }

    @Override
    protected Object parseMessageForReporter(Message msj) {
        return converter.readAsyncQueryStructure(msj);
    }
}


