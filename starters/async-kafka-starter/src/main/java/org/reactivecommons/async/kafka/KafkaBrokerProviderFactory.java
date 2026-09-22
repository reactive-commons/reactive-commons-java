package org.reactivecommons.async.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import org.reactivecommons.async.kafka.health.KafkaReactiveHealthIndicator;
import org.reactivecommons.async.kafka.validation.DomainSchemaValidatorProvider;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.broker.BrokerProviderFactory;
import org.reactivecommons.async.starter.broker.DiscardProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Service;

@Service("kafka")
@RequiredArgsConstructor
public class KafkaBrokerProviderFactory implements BrokerProviderFactory<AsyncKafkaProps> {
    private final ReactiveReplyRouter router;
    private final KafkaJacksonMessageConverter converter;
    private final MeterRegistry meterRegistry;
    private final CustomReporter errorReporter;
    private final KafkaCustomizations customizations;
    private final SslBundles sslBundles;
    private final ObjectProvider<SchemaValidator> schemaValidatorProvider;
    private final ObjectProvider<DomainSchemaValidatorProvider> domainSchemaValidatorProvider;

    @Override
    public String getBrokerType() {
        return "kafka";
    }

    @Override
    public DiscardProvider getDiscardProvider(AsyncKafkaProps props) {
        return new KafkaDiscardProvider(props, converter, customizations);
    }

    @Override
    public BrokerProvider<AsyncKafkaProps> getProvider(String domain, AsyncKafkaProps props,
                                                       DiscardProvider discardProvider) {
        SchemaValidator schemaValidator = resolveSchemaValidator(domain);
        TopologyCreator creator = KafkaSetupUtils.createTopologyCreator(props, customizations);
        ReactiveMessageSender sender = KafkaSetupUtils.createMessageSender(props, converter, creator, schemaValidator);
        ReactiveMessageListener listener = KafkaSetupUtils.createMessageListener(props, schemaValidator);
        AdminClient adminClient = AdminClient.create(props.getConnectionProperties().buildAdminProperties());
        KafkaReactiveHealthIndicator healthIndicator = new KafkaReactiveHealthIndicator(domain, adminClient);
        DiscardNotifier discardNotifier;
        if (props.isUseDiscardNotifierPerDomain()) {
            discardNotifier = KafkaSetupUtils.createDiscardNotifier(sender, converter);
        } else {
            discardNotifier = discardProvider.get();
        }
        return new KafkaBrokerProvider(domain, props, router, converter, meterRegistry, errorReporter,
                healthIndicator, listener, sender, discardNotifier, creator, customizations, sslBundles);
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
