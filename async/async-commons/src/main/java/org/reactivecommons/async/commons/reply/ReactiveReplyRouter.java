package org.reactivecommons.async.commons.reply;

import org.reactivecommons.async.commons.communications.Message;
import reactor.core.publisher.Mono;
import reactor.core.publisher.UnicastProcessor;
import reactor.util.concurrent.Queues;

import java.util.concurrent.ConcurrentHashMap;

public class ReactiveReplyRouter {
    private final ConcurrentHashMap<String, UnicastProcessor<Message>> processors = new ConcurrentHashMap<>();

    public Mono<Message> register(String correlationID) {
        final UnicastProcessor<Message> processor = UnicastProcessor.create(Queues.<Message>one().get());
        processors.put(correlationID, processor);
        return processor.singleOrEmpty();
    }

    public void routeReply(String correlationID, Message data) {
        final UnicastProcessor<Message> processor = processors.remove(correlationID);
        if (processor != null) {
            processor.onNext(data);
            processor.onComplete();
        }
    }

    public void deregister(String correlationID){
        processors.remove(correlationID);
    }

    public void routeEmpty(String correlationID) {
        final UnicastProcessor<Message> processor = processors.remove(correlationID);
        if (processor != null) {
            processor.onComplete();
        }
    }
}
