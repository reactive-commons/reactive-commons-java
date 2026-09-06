package org.reactivecommons.async.starter.impl.common.kafka.apicurio;

import org.reactivecommons.async.kafka.apicurio.SharedSchemaResolvers;
import org.reactivecommons.async.kafka.config.props.ApicurioValidationProperties;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.reactivecommons.async.kafka.validation.DomainSchemaValidatorProvider;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.reactivecommons.async.starter.exceptions.InvalidConfigurationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Registers the {@link DomainSchemaValidatorProvider} that supplies the Apicurio {@link SchemaValidator} of each
 * Reactive Commons domain.
 * <p>
 * It lives under {@code org.reactivecommons.async.starter.impl.common}, which is the package scanned
 * by {@code ReactiveCommonsConfig}, so adding this starter as a dependency is enough to enable it.
 * <p>
 * The configuration of every domain is read from {@code reactive.commons.kafka.<domain>.apicurio.registries},
 * either from the configuration files or from a {@code KafkaPropsCustomizer} bean. Every registry lists the topics
 * validated against it, each topic may override any property of its registry, and a domain that declares no
 * registry is not validated.
 */
@Configuration
public class RCKafkaApicurioConfig {

    @Bean
    @ConditionalOnMissingBean({SchemaValidator.class, DomainSchemaValidatorProvider.class})
    public DomainSchemaValidatorProvider apicurioSchemaValidatorProvider(AsyncKafkaPropsDomain propsDomain) {
        var resolvers = new SharedSchemaResolvers();
        return new ApicurioValidatorProvider(buildValidators(propsDomain, resolvers), resolvers);
    }

    /**
     * Builds the validator of every domain that declares registries, reusing the connection of the domains and
     * topics that share a registry.
     * <p>
     * They are built eagerly so that an invalid configuration fails at startup instead of when the first record of
     * that topic is handled.
     *
     * @throws InvalidConfigurationException when no domain declares a registry, because the starter would then be a
     *                                       dependency that validates nothing
     */
    static Map<String, SchemaValidator> buildValidators(AsyncKafkaPropsDomain propsDomain,
                                                        SharedSchemaResolvers resolvers) {
        Map<String, SchemaValidator> validators = new HashMap<>();
        propsDomain.forEach((domain, props) ->
                validators.put(domain, createValidator(props.getApicurio(), domain, resolvers)));
        assertSomeDomainIsValidated(validators);
        return validators;
    }

    /**
     * Rejects a configuration where no domain declares a registry.
     * <p>
     * The starter exists to validate messages against an Apicurio Registry, so having it on the classpath while no
     * domain declares one describes an intention that is not carried out: nothing would be validated, and the
     * dependency would only add the registry client to the application. A single domain may still be left
     * unvalidated by declaring no registry for it, or by disabling the validation of its registries with
     * {@code apicurio.registry.serde.validation-enabled=false}.
     */
    private static void assertSomeDomainIsValidated(Map<String, SchemaValidator> validators) {
        boolean anyValidated = validators.values().stream()
                .anyMatch(validator -> !(validator instanceof NoOpSchemaValidator));
        if (!anyValidated) {
            throw new InvalidConfigurationException("The async-commons-kafka-apicurio-starter dependency is present, "
                    + "but no domain declares reactive.commons.kafka.<domain>.apicurio.registries. Declare the "
                    + "registries and the topics validated against them, or remove the dependency and keep "
                    + "async-commons-kafka-starter.");
        }
    }

    /**
     * A domain that declares no registry keeps the default no-op validator, so the starter can be on the classpath
     * while only some domains are validated. Otherwise, its validator routes each record to the one of its topic.
     */
    private static SchemaValidator createValidator(ApicurioValidationProperties properties, String domain,
                                                   SharedSchemaResolvers resolvers) {
        if (properties == null || properties.getRegistries() == null || properties.getRegistries().isEmpty()) {
            return NoOpSchemaValidator.INSTANCE;
        }
        return ApicurioTopicValidators.create(properties.getRegistries(), domain, resolvers);
    }

    /**
     * Holds the validator of every domain and releases the registry clients they share when the context is
     * disposed. Spring infers {@code close} as the destroy method of any {@link AutoCloseable} bean.
     */
    static class ApicurioValidatorProvider implements DomainSchemaValidatorProvider, AutoCloseable {

        private final Map<String, SchemaValidator> byDomain;
        private final SharedSchemaResolvers resolvers;

        ApicurioValidatorProvider(Map<String, SchemaValidator> byDomain, SharedSchemaResolvers resolvers) {
            this.byDomain = Map.copyOf(byDomain);
            this.resolvers = resolvers;
        }

        @Override
        public SchemaValidator forDomain(String domain) {
            return byDomain.getOrDefault(domain, NoOpSchemaValidator.INSTANCE);
        }

        @Override
        public void close() {
            resolvers.close();
        }
    }
}
