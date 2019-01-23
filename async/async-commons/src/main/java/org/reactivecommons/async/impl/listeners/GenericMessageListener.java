package org.reactivecommons.async.impl.listeners;

import lombok.extern.java.Log;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;

import static java.util.function.Function.identity;
import static reactor.core.publisher.Mono.defer;

@Log
public abstract class GenericMessageListener {


    private final ConcurrentHashMap<String, Function<Message, Mono<Object>>> handlers = new ConcurrentHashMap<>();
    private final Receiver receiver;
    private final ReactiveMessageListener messageListener;
    final String queueName;
    private Scheduler scheduler = Schedulers.newParallel(getClass().getSimpleName(), 12);

    public GenericMessageListener(String queueName, ReactiveMessageListener listener) {
        this.receiver = listener.getReceiver();
        this.queueName = queueName;
        this.messageListener = listener;
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        return Mono.empty();
    }

    public void startListener() {
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
                    Mono.delay(Duration.ofMillis(350)).doOnSuccess(_n -> ((AcknowledgableDelivery) elem).nack(true)).subscribe();
                    log.log(Level.SEVERE, "Outer error protection reached for Async Consumer!! Severe Warning! ", throwable);
                    log.warning("Returning message to communications: " + ((AcknowledgableDelivery) elem).getProperties().getHeaders().toString());
                }catch (Exception e){
                    log.log(Level.SEVERE, "Error returning message in failure!", e);
                }
            }
        });
    }

    private Flux<AcknowledgableDelivery> consumeFaultTolerant(Flux<AcknowledgableDelivery> messageFlux) {
        return messageFlux.flatMap(msj ->
            handle(msj)
                .onErrorResume(err -> {
                    try {
                        log.log(Level.SEVERE, "Error encounter while processing message:", err);
                        log.warning("Returning message to communications in 200ms: " + msj.getProperties().getHeaders().toString());
                        log.warning(new String(msj.getBody()));
                    } catch (Exception e) {
                        log.log(Level.SEVERE, "Log Error", e);
                    }
                    return Mono.just(msj).delayElement(Duration.ofMillis(200)).doOnNext(s -> msj.nack(true));
                }).doOnSuccess(s -> msj.ack())
        );
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



}


