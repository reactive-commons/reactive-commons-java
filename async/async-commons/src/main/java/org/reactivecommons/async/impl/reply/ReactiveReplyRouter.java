package org.reactivecommons.async.impl.reply;

import reactor.core.publisher.Mono;
import reactor.core.publisher.UnicastProcessor;
import reactor.util.concurrent.Queues;

import java.util.concurrent.ConcurrentHashMap;

public class ReactiveReplyRouter {
    private final ConcurrentHashMap<String, UnicastProcessor<String>> processors = new ConcurrentHashMap<>();

    public Mono<String> register(String correlationID) {
        final UnicastProcessor<String> processor = UnicastProcessor.create(Queues.<String>one().get());
        processors.put(correlationID, processor);
        return processor.singleOrEmpty();
    }

    public void routeReply(String correlationID, String data) {
        final UnicastProcessor<String> processor = processors.remove(correlationID);
        if (processor != null) {
            processor.onNext(data);
            processor.onComplete();
        }
    }

    public <E> void routeError(String correlationID, String data) {
        final UnicastProcessor<String> processor = processors.remove(correlationID);
        if (processor != null) {
            processor.onError(new RuntimeException(data));
        }
    }

    public void routeEmpty(String correlationID) {
        final UnicastProcessor<String> processor = processors.remove(correlationID);
        if (processor != null) {
            processor.onComplete();
        }
    }
}
