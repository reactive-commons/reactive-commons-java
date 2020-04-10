package org.reactivecommons.async.impl.listeners;

import com.rabbitmq.client.AMQP;
import lombok.extern.java.Log;
import org.reactivecommons.async.impl.FallbackStrategy;
import org.reactivecommons.async.impl.RabbitMessage;
import org.reactivecommons.async.impl.communications.Message;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.Receiver;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;

import static java.lang.String.format;
import static java.util.function.Function.identity;
import static reactor.core.publisher.Mono.defer;

@Log
public abstract class GenericMessageListener {


    private final ConcurrentHashMap<String, Function<Message, Mono<Object>>> handlers = new ConcurrentHashMap<>();
    private final Receiver receiver;
    private final ReactiveMessageListener messageListener;
    final String queueName;
    private Scheduler scheduler = Schedulers.newParallel(getClass().getSimpleName(), 12);
    private final boolean useDLQRetries;
    private final long maxRetries;

    public GenericMessageListener(String queueName, ReactiveMessageListener listener, boolean useDLQRetries, long maxRetries) {
        this.receiver = listener.getReceiver();
        this.queueName = queueName;
        this.messageListener = listener;
        this.maxRetries = maxRetries;
        this.useDLQRetries = useDLQRetries;
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        return Mono.empty();
    }

    public void startListener() {
        log.log(Level.INFO, "Using max concurrency {0}, in queue: {1}", new Object[]{messageListener.getMaxConcurrency(), queueName});
        if (useDLQRetries){
            log.log(Level.INFO, "ATTENTION! Using DLQ Strategy for retries with {0} + 1 Max Retries configured!", new Object[]{maxRetries});
        }else {
            log.log(Level.INFO, "ATTENTION! Using infinite fast retries as Retry Strategy");
        }
        setUpBindings(messageListener.getTopologyCreator()).thenMany(
        receiver.consumeManualAck(queueName)
            .transform(this::consumeFaultTolerant)
            .transform(this::outerFailureProtection))
            .subscribe();
    }


    private Mono<AcknowledgableDelivery> handle(AcknowledgableDelivery msj) {
        final Function<Message, Mono<Object>> handler = getExecutor(getExecutorPath(msj));
        final Message message = RabbitMessage.fromDelivery(msj);
        return defer(() -> handler.apply(message)).transform(enrichPostProcess(message))
            .subscribeOn(scheduler).thenReturn(msj);
    }


    private <T> Flux<T> outerFailureProtection(Flux<T> messageFlux) {
        return messageFlux.onErrorContinue(t -> true, (throwable, elem) -> {
            if(elem instanceof AcknowledgableDelivery){
                try {
                    String messageID = ((AcknowledgableDelivery) elem).getProperties().getMessageId();
                    log.log(Level.SEVERE, format("ATTENTION !! Outer error protection reached for %s, in Async Consumer!! Severe Warning! ", messageID));
                    requeueOrAck((AcknowledgableDelivery) elem, throwable).subscribe();
                }catch (Exception e){
                    log.log(Level.SEVERE, "Error returning message in failure!", e);
                }
            }
        });
    }

    private Flux<AcknowledgableDelivery> consumeFaultTolerant(Flux<AcknowledgableDelivery> messageFlux) {
        return messageFlux.flatMap(msj ->
            handle(msj)
                .doOnSuccess(AcknowledgableDelivery::ack)
                .onErrorResume(err -> requeueOrAck(msj, err))
        , messageListener.getMaxConcurrency());
    }


    protected void logError(Throwable err, AcknowledgableDelivery msj, FallbackStrategy strategy){
        String messageID = msj.getProperties().getMessageId();
        try {
            log.log(Level.SEVERE, format("Error encounter while processing message %s: %s", messageID, err.toString()));
            log.warning(format("Message %s Headers: %s", messageID, msj.getProperties().getHeaders().toString()));
            log.warning(format("Message %s Body: %s", messageID, new String(msj.getBody())));
        } catch (Exception e) {
            log.log(Level.SEVERE, "Error Login message Content!!", e);
        }finally {
            log.warning(format(strategy.message, messageID));
        }
    }

    private Function<Message, Mono<Object>> getExecutor(String path) {
        final Function<Message, Mono<Object>> handler = handlers.get(path);
        return handler != null ? handler : computeRawMessageHandler(path);
    }

    private Function<Message, Mono<Object>> computeRawMessageHandler(String commandId) {
        return handlers.computeIfAbsent(commandId, s ->
            rawMessageHandler(commandId)
        );
    }

    protected abstract Function<Message, Mono<Object>> rawMessageHandler(String executorPath);
    protected abstract String getExecutorPath(AcknowledgableDelivery msj);

    protected Function<Mono<Object>, Mono<Object>> enrichPostProcess(Message msg){
        return identity();
    }

    private Mono<AcknowledgableDelivery> requeueOrAck(AcknowledgableDelivery msj, Throwable err) {
        Long retryNumber = getRetryNumber(msj);
        if ((msj.getEnvelope().isRedeliver() || retryNumber > 0) && useDLQRetries) {
            if (retryNumber >= maxRetries) {
                //Send Message to final DLQ
                logError(err, msj, FallbackStrategy.DEFINITIVE_DISCARD);
                msj.ack();
            } else {
                logError(err, msj, FallbackStrategy.RETRY_DLQ);
                msj.nack(false);
            }
            return Mono.just(msj);
        }else {
            logError(err, msj, FallbackStrategy.FAST_RETRY);
            return Mono.just(msj).delayElement(Duration.ofMillis(200)).doOnNext(m -> m.nack(true));
        }
    }

    private Long getRetryNumber(AcknowledgableDelivery delivery) {
        return Optional.ofNullable(delivery.getProperties())
                .map(AMQP.BasicProperties::getHeaders)
                .map(x -> (List<HashMap>)x.get("x-death"))
                .filter(list -> !list.isEmpty())
                .map(list -> list.get(0))
                .map(hashMap -> (Long) hashMap.get("count"))
                .orElse(0L);
    }

}


