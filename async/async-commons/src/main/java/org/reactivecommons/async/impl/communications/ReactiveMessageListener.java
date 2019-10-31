package org.reactivecommons.async.impl.communications;

import reactor.rabbitmq.Receiver;

public class ReactiveMessageListener {

    private final Receiver receiver;
    private final TopologyCreator topologyCreator;
    private final Integer maxConcurrency;
    private final Integer prefetch;

    public ReactiveMessageListener(Receiver receiver, TopologyCreator topologyCreator) {
        this(receiver, topologyCreator, 250, 250);
    }

    public ReactiveMessageListener(Receiver receiver, TopologyCreator topologyCreator, Integer maxConcurrency) {
        this(receiver, topologyCreator, maxConcurrency, 250 );
    }

    public ReactiveMessageListener(Receiver receiver, TopologyCreator topologyCreator, Integer maxConcurrency, Integer prefetch) {
        this.receiver = receiver;
        this.topologyCreator = topologyCreator;
        this.maxConcurrency = maxConcurrency;
        this.prefetch = prefetch;
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

    public Integer getPrefetch() {
        return prefetch;
    }
}

