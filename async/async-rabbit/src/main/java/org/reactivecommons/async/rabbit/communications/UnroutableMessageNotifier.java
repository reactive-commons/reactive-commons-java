package org.reactivecommons.async.rabbit.communications;

import lombok.extern.java.Log;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;

import java.util.concurrent.atomic.AtomicReference;

@Log
public class UnroutableMessageNotifier {
    private final Sinks.Many<OutboundMessageResult<OutboundMessage>> sink;
    private final AtomicReference<Disposable> currentSubscription = new AtomicReference<>();

    public UnroutableMessageNotifier() {
        this.sink = Sinks.many().multicast().onBackpressureBuffer();
    }

    public void notifyUnroutableMessage(OutboundMessageResult<OutboundMessage> message) {
        if (sink.tryEmitNext(message).isFailure()) {
            log.warning("Failed to emit unroutable message: " + message);
        }
    }

    public void listenToUnroutableMessages(UnroutableMessageHandler handler) {
        Disposable previous = currentSubscription.getAndSet(sink.asFlux()
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(handler::processMessage)
                .onErrorContinue((throwable, o) ->
                        log.severe("Error processing unroutable message: " + throwable.getMessage())
                )
                .subscribe());
        if (previous != null && !previous.isDisposed()) {
            previous.dispose();
        }
    }
}
