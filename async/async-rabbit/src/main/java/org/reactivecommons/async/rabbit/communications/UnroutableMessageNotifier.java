package org.reactivecommons.async.rabbit.communications;

import lombok.extern.java.Log;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.OutboundMessageResult;

@Log
public class UnroutableMessageNotifier {
    private final Sinks.Many<OutboundMessageResult<MyOutboundMessage>> sink;
    private volatile Disposable currentSubscription;

    public UnroutableMessageNotifier() {
        this.sink = Sinks.many().multicast().onBackpressureBuffer();
    }

    public void notifyUnroutableMessage(OutboundMessageResult<MyOutboundMessage> message) {
        if (sink.tryEmitNext(message).isFailure()) {
            log.warning("Failed to emit unroutable message: " + message);
        }
    }

    public void listenToUnroutableMessages(UnroutableMessageHandler handler) {
        if (currentSubscription != null && !currentSubscription.isDisposed()) {
            currentSubscription.dispose();
        }
        currentSubscription = sink.asFlux()
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(handler::processMessage)
                .onErrorContinue((throwable, o) ->
                        log.severe("Error processing unroutable message: " + throwable.getMessage())
                )
                .subscribe();
    }
}
