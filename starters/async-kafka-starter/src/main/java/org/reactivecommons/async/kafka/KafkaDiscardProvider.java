package org.reactivecommons.async.kafka;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.starter.broker.DiscardProvider;

/**
 * The discard notifier shared by the domains that do not use their own one. It creates its sender and its topology
 * creator on the first use, and closes them once the last broker provider using it closes (see {@link #retain()} and
 * {@link #release()}).
 */
@RequiredArgsConstructor
public class KafkaDiscardProvider implements DiscardProvider {
    private final AsyncKafkaProps props;
    private final MessageConverter converter;
    private final KafkaCustomizations customizations;
    private DiscardNotifier discardNotifier;
    private TopologyCreator creator;
    private ReactiveMessageSender sender;
    private int users;

    @Override
    public synchronized DiscardNotifier get() {
        if (discardNotifier == null) {
            creator = KafkaSetupUtils.createTopologyCreator(props, customizations);
            sender = KafkaSetupUtils.createMessageSender(props, converter, creator);
            discardNotifier = KafkaSetupUtils.createDiscardNotifier(sender, converter);
        }
        return discardNotifier;
    }

    /**
     * Registers a broker provider that publishes its discards through this provider. Call {@link #release()} when it
     * closes.
     */
    public synchronized void retain() {
        users++;
    }

    /**
     * Unregisters a broker provider. Once none is left, the sender and the topology creator are closed: nobody
     * publishes through them anymore.
     */
    public synchronized void release() {
        if (users > 0 && --users == 0 && sender != null) {
            sender.close();
            creator.close();
            sender = null;
            creator = null;
            discardNotifier = null;
        }
    }
}
