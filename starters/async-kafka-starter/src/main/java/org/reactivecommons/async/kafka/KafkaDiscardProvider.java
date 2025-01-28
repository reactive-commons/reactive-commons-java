package org.reactivecommons.async.kafka;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.starter.broker.DiscardProvider;
import org.springframework.boot.ssl.SslBundles;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class KafkaDiscardProvider implements DiscardProvider {
    private final AsyncKafkaProps props;
    private final MessageConverter converter;
    private final KafkaCustomizations customizations;
    private final SslBundles sslBundles;
    private final Map<Boolean, DiscardNotifier> discardNotifier = new ConcurrentHashMap<>();

    @Override
    public DiscardNotifier get() {
        return discardNotifier.computeIfAbsent(true, this::buildDiscardNotifier);
    }

    private DiscardNotifier buildDiscardNotifier(boolean ignored) {
        TopologyCreator creator = KafkaSetupUtils.createTopologyCreator(props, customizations, sslBundles);
        ReactiveMessageSender sender = KafkaSetupUtils.createMessageSender(props, converter, creator, sslBundles);
        return KafkaSetupUtils.createDiscardNotifier(sender, converter);
    }
}
