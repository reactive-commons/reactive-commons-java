package org.reactivecommons.async.kafka;

import lombok.extern.slf4j.Slf4j;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import org.reactivecommons.async.kafka.health.KafkaReactiveHealthIndicator;
import org.reactivecommons.async.kafka.metrics.KafkaDomainMetrics;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.broker.BrokerProviderFactory;
import org.reactivecommons.async.starter.broker.DiscardProvider;
import org.springframework.stereotype.Service;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Slf4j
@Service("kafka")
public class KafkaBrokerProviderFactory implements BrokerProviderFactory<AsyncKafkaProps> {
    private final KafkaJacksonMessageConverter converter;
    private final CustomReporter errorReporter;
    private final KafkaCustomizations customizations;
    private final KafkaSchemaValidators schemaValidators;
    private final KafkaClientSettingsFactory clientSettings;

    /**
     * @param converter        converts the messages of every domain
     * @param errorReporter    reports the errors of the listeners
     * @param customizations   the configuration of the topics the domains create
     * @param schemaValidators the {@link SchemaValidator} of each domain
     * @param clientSettings   the SSL bundles, options customizers and metrics the clients of each domain are built
     *                         with
     */
    public KafkaBrokerProviderFactory(KafkaJacksonMessageConverter converter, CustomReporter errorReporter,
                                      KafkaCustomizations customizations, KafkaSchemaValidators schemaValidators,
                                      KafkaClientSettingsFactory clientSettings) {
        this.converter = converter;
        this.errorReporter = errorReporter;
        this.customizations = customizations;
        this.schemaValidators = schemaValidators;
        this.clientSettings = clientSettings;
    }

    @Override
    public String getBrokerType() {
        return "kafka";
    }

    /**
     * The discard notifier shared by the domains that do not use their own one: it publishes with the properties of
     * the default domain.
     */
    @Override
    public DiscardProvider getDiscardProvider(AsyncKafkaProps props) {
        return new KafkaDiscardProvider(props, converter, customizations,
                () -> clientSettings.forDomain(DEFAULT_DOMAIN, props));
    }

    /**
     * Creates the clients of the domain without connecting to its cluster: the application starts even when the
     * broker cannot be reached. The domain has a single admin client, shared by its topology creator and its health
     * check; the default domain shares it with the discard notifier it publishes the discards of the other domains
     * with.
     */
    @Override
    public BrokerProvider<AsyncKafkaProps> getProvider(String domain, AsyncKafkaProps props,
                                                       DiscardProvider discardProvider) {
        warnIgnoredProperties(domain, props);
        SchemaValidator schemaValidator = schemaValidators.forDomain(domain);
        KafkaClientSettings settings = clientSettings.forDomain(domain, props);
        // First: it checks the consumer properties, so a mistake fails before any client is created
        ReactiveMessageListener listener = KafkaSetupUtils.createMessageListener(props, schemaValidator, settings);
        KafkaDiscardProvider shared = discardProvider instanceof KafkaDiscardProvider kafkaDiscardProvider
                ? kafkaDiscardProvider : null;
        TopologyCreator creator = shared != null && shared.usesPropertiesOf(props)
                ? shared.shareTopologyCreator()
                : KafkaSetupUtils.createTopologyCreator(props, customizations, settings);
        ReactiveMessageSender sender = KafkaSetupUtils.createMessageSender(props, converter, creator, schemaValidator,
                settings);
        KafkaReactiveHealthIndicator healthIndicator = new KafkaReactiveHealthIndicator(domain, creator.adminClient(),
                props.getHealthTimeout());
        DiscardNotifier discardNotifier;
        Runnable releaseDiscardProvider = () -> {
        };
        if (props.isUseDiscardNotifierPerDomain()) {
            discardNotifier = KafkaSetupUtils.createDiscardNotifier(sender, converter);
        } else {
            discardNotifier = discardProvider.get();
            if (shared != null) {
                shared.retain();
                releaseDiscardProvider = shared::release;
            }
        }
        Runnable release = releaseDiscardProvider;
        KafkaDomainMetrics metrics = settings.metrics();
        return new KafkaBrokerProvider(domain, props, new KafkaBrokerProvider.Components(converter, errorReporter,
                healthIndicator, listener, sender, discardNotifier, creator), () -> {
            release.run();
            metrics.close();
        });
    }

    /**
     * A property that is bound and has no effect would make one believe a behaviour was changed: it is reported once,
     * at startup, by its name only, since its value may be a secret.
     */
    private static void warnIgnoredProperties(String domain, AsyncKafkaProps props) {
        KafkaSetupUtils.ignoredProperties(props.getConnectionProperties()).forEach(property ->
                log.warn("Kafka domain {}: connectionProperties.{} is configured but has no effect, see the "
                        + "properties that do not apply in the Kafka configuration documentation", domain, property));
    }
}
