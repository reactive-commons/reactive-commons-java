package org.reactivecommons.async.impl.communications;

import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.Sender;

public class ReactiveMessageListener {

    private final Receiver receiver;
    private final TopologyCreator topologyCreator;

    public ReactiveMessageListener(Receiver receiver, TopologyCreator topologyCreator) {
        this.receiver = receiver;
        this.topologyCreator = topologyCreator;
    }

    public TopologyCreator getTopologyCreator() {
        return topologyCreator;
    }

    public Receiver getReceiver() {
        return receiver;
    }
}

