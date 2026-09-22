package org.reactivecommons.async.kafka.validation;

/**
 * Resolves the {@link SchemaValidator} that must be applied to a given Reactive Commons domain.
 */
@FunctionalInterface
public interface DomainSchemaValidatorProvider {

    /**
     * @param domain name of the domain.
     * @return the validator for that domain, never {@code null}. Use {@link NoOpSchemaValidator#INSTANCE} to skip
     * the validation of a particular domain.
     */
    SchemaValidator forDomain(String domain);
}
