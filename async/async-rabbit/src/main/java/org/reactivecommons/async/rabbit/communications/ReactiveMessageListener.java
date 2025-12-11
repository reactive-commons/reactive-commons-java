package org.reactivecommons.async.rabbit.communications;

import reactor.rabbitmq.Receiver;


public record ReactiveMessageListener(Receiver receiver, TopologyCreator topologyCreator, Integer maxConcurrency,
                                      Integer prefetchCount) {

    public ReactiveMessageListener(Receiver receiver, TopologyCreator topologyCreator) {
        this(receiver, topologyCreator, 250, 250);
    }

}

