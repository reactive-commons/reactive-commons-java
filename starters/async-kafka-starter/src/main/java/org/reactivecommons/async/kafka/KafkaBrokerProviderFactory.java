package org.reactivecommons.async.kafka;

import lombok.extern.slf4j.Slf4j;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.KafkaReceiverOptionsCustomizer;
import org.reactivecommons.async.kafka.config.KafkaSenderOptionsCustomizer;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import org.reactivecommons.async.kafka.health.KafkaReactiveHealthIndicator;
import org.reactivecommons.async.kafka.metrics.KafkaDomainMetrics;
import org.reactivecommons.async.kafka.metrics.KafkaMetricsFactory;
import org.reactivecommons.async.kafka.validation.DomainSchemaValidatorProvider;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.broker.BrokerProviderFactory;
import org.reactivecommons.async.starter.broker.DiscardProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Service;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Slf4j
@Service("kafka")
public class KafkaBrokerProviderFactory implements BrokerProviderFactory<AsyncKafkaProps> {
    private final KafkaJacksonMessageConverter converter;
    private final CustomReporter errorReporter;
    private final KafkaCustomizations customizations;
    private final ObjectProvider<SchemaValidator> schemaValidatorProvider;
    private final ObjectProvider<DomainSchemaValidatorProvider> domainSchemaValidatorProvider;
    private final ObjectProvider<SslBundles> sslBundles;
    private final ObjectProvider<KafkaReceiverOptionsCustomizer> receiverCustomizers;
    private final ObjectProvider<KafkaSenderOptionsCustomizer> senderCustomizers;
    private final ObjectProvider<KafkaMetricsFactory> metricsFactory;

    /**
     * Without SSL bundles, options customizers nor metrics.
     */
    public KafkaBrokerProviderFactory(KafkaJacksonMessageConverter converter, CustomReporter errorReporter,
                                      KafkaCustomizations customizations,
                                      ObjectProvider<SchemaValidator> schemaValidatorProvider,
                                      ObjectProvider<DomainSchemaValidatorProvider> domainSchemaValidatorProvider) {
        this(converter, errorReporter, customizations, schemaValidatorProvider, domainSchemaValidatorProvider,
                none(SslBundles.class), none(KafkaReceiverOptionsCustomizer.class),
                none(KafkaSenderOptionsCustomizer.class), none(KafkaMetricsFactory.class));
    }

    /**
     * @param sslBundles          resolves {@code ssl.bundle} of the connection properties
     * @param receiverCustomizers applied, in order, to the options of the consumers of every domain
     * @param senderCustomizers   applied, in order, to the options of the producer of every domain
     * @param metricsFactory      the metrics of each domain; none when absent
     */
    @Autowired
    public KafkaBrokerProviderFactory(KafkaJacksonMessageConverter converter, CustomReporter errorReporter,
                                      KafkaCustomizations customizations,
                                      ObjectProvider<SchemaValidator> schemaValidatorProvider,
                                      ObjectProvider<DomainSchemaValidatorProvider> domainSchemaValidatorProvider,
                                      ObjectProvider<SslBundles> sslBundles,
                                      ObjectProvider<KafkaReceiverOptionsCustomizer> receiverCustomizers,
                                      ObjectProvider<KafkaSenderOptionsCustomizer> senderCustomizers,
                                      ObjectProvider<KafkaMetricsFactory> metricsFactory) {
        this.converter = converter;
        this.errorReporter = errorReporter;
        this.customizations = customizations;
        this.schemaValidatorProvider = schemaValidatorProvider;
        this.domainSchemaValidatorProvider = domainSchemaValidatorProvider;
        this.sslBundles = sslBundles;
        this.receiverCustomizers = receiverCustomizers;
        this.senderCustomizers = senderCustomizers;
        this.metricsFactory = metricsFactory;
    }

    private static <T> ObjectProvider<T> none(Class<T> type) {
        return new StaticListableBeanFactory().getBeanProvider(type);
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
                () -> clientSettings(DEFAULT_DOMAIN, props));
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
        SchemaValidator schemaValidator = resolveSchemaValidator(domain);
        KafkaClientSettings settings = clientSettings(domain, props);
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

    private KafkaClientSettings clientSettings(String domain, AsyncKafkaProps props) {
        KafkaMetricsFactory factory = metricsFactory.getIfAvailable();
        KafkaDomainMetrics metrics = factory == null ? KafkaDomainMetrics.NONE : factory.forDomain(domain);
        return new KafkaClientSettings(domain, KafkaClientSettings.clientIdBase(props), sslBundles.getIfAvailable(),
                receiverCustomizers.orderedStream().toList(), senderCustomizers.orderedStream().toList(), metrics);
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

    /**
     * A {@link SchemaValidator} bean is a global override and wins for every domain. Otherwise the validator is
     * asked per domain, so each one can be validated against its own registry and artifacts.
     */
    private SchemaValidator resolveSchemaValidator(String domain) {
        SchemaValidator global = schemaValidatorProvider.getIfAvailable();
        if (global != null) {
            return global;
        }
        DomainSchemaValidatorProvider provider = domainSchemaValidatorProvider.getIfAvailable();
        if (provider == null) {
            return NoOpSchemaValidator.INSTANCE;
        }
        SchemaValidator validator = provider.forDomain(domain);
        return validator == null ? NoOpSchemaValidator.INSTANCE : validator;
    }

}
