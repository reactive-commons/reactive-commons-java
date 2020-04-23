package org.reactivecommons.async.impl.communications;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import reactor.rabbitmq.Receiver;


@Getter
@RequiredArgsConstructor
public class ReactiveMessageListener {

    private final Receiver receiver;
    private final TopologyCreator topologyCreator;
    private final Integer maxConcurrency;
    private final Integer prefetchCount;

    public ReactiveMessageListener(Receiver receiver, TopologyCreator topologyCreator) {
        this(receiver, topologyCreator, 250, 250);
    }

}

