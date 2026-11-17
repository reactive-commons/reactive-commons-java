package org.reactivecommons.async.kafka;

import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.starter.broker.DiscardProvider;

import java.util.function.Supplier;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

/**
 * The discard notifier shared by the domains that do not use their own one, built from the properties of the default
 * domain. It creates its sender (whose producer is created on the first discard) on the first use, and closes it once
 * the last broker provider using it closes (see {@link #retain()} and {@link #release()}).
 * <p>
 * Its topology creator, and so the admin client, is shared with the default domain (see
 * {@link #shareTopologyCreator()}): the domain does not open a second admin client to the same cluster.
 */
public class KafkaDiscardProvider implements DiscardProvider {
    private final AsyncKafkaProps props;
    private final MessageConverter converter;
    private final KafkaCustomizations customizations;
    private final Supplier<KafkaClientSettings> settingsFactory;
    private KafkaClientSettings settings;
    private DiscardNotifier discardNotifier;
    /**
     * The creator built from the properties of this provider, shared with the default domain; it may be closed.
     */
    private TopologyCreator creator;
    /**
     * The creator used by the sender, of which this provider holds one reference.
     */
    private TopologyCreator senderCreator;
    private ReactiveMessageSender sender;
    private int users;

    public KafkaDiscardProvider(AsyncKafkaProps props, MessageConverter converter,
                                KafkaCustomizations customizations) {
        this(props, converter, customizations, () -> KafkaClientSettings.defaults(DEFAULT_DOMAIN, props));
    }

    /**
     * @param settingsFactory what the clients are built with, besides the properties; asked once, on first use
     */
    public KafkaDiscardProvider(AsyncKafkaProps props, MessageConverter converter,
                                KafkaCustomizations customizations, Supplier<KafkaClientSettings> settingsFactory) {
        this.props = props;
        this.converter = converter;
        this.customizations = customizations;
        this.settingsFactory = settingsFactory;
    }

    @Override
    public synchronized DiscardNotifier get() {
        if (discardNotifier == null) {
            senderCreator = acquireTopologyCreator();
            sender = KafkaSetupUtils.createMessageSender(props, converter, senderCreator,
                    NoOpSchemaValidator.INSTANCE, settings());
            discardNotifier = KafkaSetupUtils.createDiscardNotifier(sender, converter);
        }
        return discardNotifier;
    }

    /**
     * Whether this provider is built from these properties, i.e. they are those of the default domain.
     */
    public boolean usesPropertiesOf(AsyncKafkaProps domainProps) {
        return props == domainProps;
    }

    /**
     * The topology creator of this provider, for the domain whose properties it uses: both share one admin client.
     * The caller closes it once ({@link TopologyCreator#close()}); the admin client is closed when every user closed
     * it.
     */
    public synchronized TopologyCreator shareTopologyCreator() {
        return acquireTopologyCreator();
    }

    /**
     * Registers a broker provider that publishes its discards through this provider. Call {@link #release()} when it
     * closes.
     */
    public synchronized void retain() {
        users++;
    }

    /**
     * Unregisters a broker provider. Once none is left, the sender is closed, and the topology creator unless the
     * default domain still uses it: nobody publishes through them anymore.
     */
    public synchronized void release() {
        if (users > 0 && --users == 0 && sender != null) {
            sender.close();
            senderCreator.close();
            settings.metrics().close();
            sender = null;
            senderCreator = null;
            discardNotifier = null;
            settings = null;
        }
    }

    /**
     * One more reference to the creator, which the caller closes; a new one when there is none or it was closed.
     */
    private TopologyCreator acquireTopologyCreator() {
        if (creator == null || !creator.retain()) {
            creator = KafkaSetupUtils.createTopologyCreator(props, customizations, settings());
        }
        return creator;
    }

    private KafkaClientSettings settings() {
        if (settings == null) {
            settings = settingsFactory.get();
        }
        return settings;
    }
}
