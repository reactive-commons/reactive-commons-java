package org.reactivecommons.async.kafka;

import org.reactivecommons.async.kafka.validation.DomainSchemaValidatorProvider;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Chooses the {@link SchemaValidator} of each domain from the beans of the application.
 */
public final class KafkaSchemaValidators {
    private final ObjectProvider<SchemaValidator> schemaValidatorProvider;
    private final ObjectProvider<DomainSchemaValidatorProvider> domainSchemaValidatorProvider;

    /**
     * @param schemaValidatorProvider       a global validator, used by every domain
     * @param domainSchemaValidatorProvider the validator of each domain, asked when there is no global one
     */
    public KafkaSchemaValidators(ObjectProvider<SchemaValidator> schemaValidatorProvider,
                                 ObjectProvider<DomainSchemaValidatorProvider> domainSchemaValidatorProvider) {
        this.schemaValidatorProvider = schemaValidatorProvider;
        this.domainSchemaValidatorProvider = domainSchemaValidatorProvider;
    }

    /**
     * A {@link SchemaValidator} bean is a global override and wins for every domain. Otherwise the validator is
     * asked per domain, so each one can be validated against its own registry and artifacts.
     *
     * @param domain the domain whose records are validated
     * @return the validator of the domain; {@link NoOpSchemaValidator#INSTANCE} when there is none
     */
    public SchemaValidator forDomain(String domain) {
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
