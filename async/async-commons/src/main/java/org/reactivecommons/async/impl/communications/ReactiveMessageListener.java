package org.reactivecommons.async.impl.communications;

import reactor.rabbitmq.Receiver;

public class ReactiveMessageListener {

    private final Receiver receiver;
    private final TopologyCreator topologyCreator;
    private final Integer maxConcurrency;

    public ReactiveMessageListener(Receiver receiver, TopologyCreator topologyCreator) {
        this(receiver, topologyCreator, 250);
    }

    public ReactiveMessageListener(Receiver receiver, TopologyCreator topologyCreator, Integer maxConcurrency) {
        this.receiver = receiver;
        this.topologyCreator = topologyCreator;
        this.maxConcurrency = maxConcurrency;
    }

    public TopologyCreator getTopologyCreator() {
        return topologyCreator;
    }

    public Receiver getReceiver() {
        return receiver;
    }

    public Integer getMaxConcurrency() {
        return maxConcurrency;
    }
}

