package org.reactivecommons.async.starter.impl.common.kafka.apicurio;

import io.apicurio.registry.resolver.config.SchemaResolverConfig;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.apicurio.registry.serde.kafka.config.KafkaSerdeConfig;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.reactivecommons.async.kafka.apicurio.ApicurioSchemaValidatorFactory;
import org.reactivecommons.async.kafka.apicurio.SharedSchemaResolvers;
import org.reactivecommons.async.kafka.apicurio.TopicSchemaValidatorRouter;
import org.reactivecommons.async.kafka.config.props.ApicurioRegistry;
import org.reactivecommons.async.kafka.config.props.ApicurioTopic;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.reactivecommons.async.starter.exceptions.InvalidConfigurationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the validator of every topic declared under {@code reactive.commons.kafka.<domain>.apicurio.registries}.
 * <p>
 * The properties of a registry are the defaults of every topic it declares, and a topic overrides them key by key.
 * That is what lets a topic name only its artifact id, its group id or its version while inheriting the endpoint and
 * the credentials.
 * <p>
 * A declared topic is validated in both directions, and a topic that is not declared, or that sets
 * {@code apicurio.registry.serde.validation-enabled=false}, is not validated at all.
 * <p>
 * Topics resolving against the same registry share one connection and one schema cache, even when they read
 * different groups, because the artifact coordinates are resolved per record and the cache is indexed by the full
 * coordinates.
 * <p>
 * Every problem is reported with the exact configuration path that has to be fixed, and eagerly, so an invalid
 * declaration fails at startup and not when the first record of that topic is handled.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ApicurioTopicValidators {

    /**
     * @param registries the registries declared by the domain, each one with its topics
     * @param domain     name of the domain the registries belong to
     * @param resolvers  the registry connections to reuse, shared by every domain and owned by the caller
     * @return the validator that routes each record to the validator of its topic, leaving undeclared topics
     * unvalidated
     */
    static TopicSchemaValidatorRouter create(List<ApicurioRegistry> registries, String domain,
                                             SharedSchemaResolvers resolvers) {
        return new TopicSchemaValidatorRouter(buildValidators(registries, domain, resolvers));
    }

    /**
     * @return the validator of every topic declared by the domain, indexed by topic name
     */
    static Map<String, SchemaValidator> buildValidators(List<ApicurioRegistry> registries, String domain,
                                                        SharedSchemaResolvers resolvers) {
        Map<String, SchemaValidator> validators = new HashMap<>();
        if (registries == null || registries.isEmpty()) {
            return validators;
        }
        Map<String, String> declaredBy = new HashMap<>();
        for (int i = 0; i < registries.size(); i++) {
            addTopicsOf(registries.get(i), path(domain, i), domain, validators, declaredBy, resolvers);
        }
        return validators;
    }

    private static String path(String domain, int registryIndex) {
        return "reactive.commons.kafka." + domain + ".apicurio.registries[" + registryIndex + "]";
    }

    private static void addTopicsOf(ApicurioRegistry registry, String registryPath, String domain,
                                    Map<String, SchemaValidator> validators, Map<String, String> declaredBy,
                                    SharedSchemaResolvers resolvers) {
        if (registry == null) {
            throw new InvalidConfigurationException(registryPath + " is empty. Declare its name, its properties and "
                    + "the topics validated against it, or remove it.");
        }
        String registryLabel = labelOf(registry.getName(), registryPath);
        List<ApicurioTopic> topics = registry.getTopics();
        if (topics == null || topics.isEmpty()) {
            throw new InvalidConfigurationException(registryLabel + " declares no topics, so Reactive Commons would "
                    + "open a connection to the Apicurio Registry without validating anything. List the topics to "
                    + "validate under " + registryPath + ".topics, or remove the registry.");
        }
        for (int j = 0; j < topics.size(); j++) {
            ApicurioTopic topic = topics.get(j);
            String topicPath = registryPath + ".topics[" + j + "]";
            if (topic == null || !isSet(topic.getName())) {
                throw new InvalidConfigurationException(topicPath + " of " + registryLabel + " has no name. Set "
                        + topicPath + ".name to the Kafka topic to validate.");
            }
            String name = topic.getName().trim();
            String previous = declaredBy.put(name, registryLabel);
            if (previous != null) {
                throw new InvalidConfigurationException("Topic '" + name + "' of domain " + domain + " is declared "
                        + "by " + previous + " and by " + registryLabel + ", so it has two schema configurations "
                        + "and neither of them can be chosen: a record only carries its topic name. Declare the "
                        + "topic once per domain, under the registry that validates it. The same topic name may be "
                        + "declared by another domain, against another registry.");
            }
            validators.put(name, createValidator(registry, topic, name, topicPath, registryLabel, resolvers));
        }
    }

    /**
     * Whether the topic is validated at all, decided by {@code apicurio.registry.serde.validation-enabled} exactly
     * as it would be for the Apicurio serdes. When it is {@code false} the topic keeps the no-op validator instead
     * of connecting to the registry, which is also what an undeclared topic gets.
     * <p>
     * A validated topic is validated in both directions: on publish, which is also what writes the schema
     * coordinates in the record headers, and on consume. To validate a single direction, declare a
     * {@code SchemaValidator} or a {@code DomainSchemaValidatorProvider} bean that delegates to a validator built
     * with {@code ApicurioSchemaValidator.builder()}.
     */
    private static SchemaValidator createValidator(ApicurioRegistry registry, ApicurioTopic topic,
                                                   String name, String topicPath, String registryLabel,
                                                   SharedSchemaResolvers resolvers) {
        Map<String, Object> effective = merge(registry.getProperties(), topic.getProperties());
        if (isValidationDisabled(effective)) {
            return NoOpSchemaValidator.INSTANCE;
        }
        assertRegistryUrlIsSet(effective, name, topicPath, registryLabel);
        assertHeadersAreEnabled(effective, name, topicPath);
        assertResolverStrategyIsRecognised(effective, name, topicPath);
        assertVersionIsResolvable(effective, name, topicPath);

        return ApicurioSchemaValidatorFactory.create(
                resolvers.forRegistry(ApicurioSchemaValidatorFactory.registryConfig(effective)),
                effective, null);
    }

    /**
     * @return the properties of the registry with the ones of the topic applied over them
     */
    private static Map<String, Object> merge(Map<String, String> registryProperties,
                                             Map<String, String> topicProperties) {
        Map<String, Object> effective = new HashMap<>();
        putAll(effective, registryProperties);
        putAll(effective, topicProperties);
        return effective;
    }

    private static void putAll(Map<String, Object> target, Map<String, String> source) {
        if (source != null) {
            target.putAll(source);
        }
    }

    private static boolean isValidationDisabled(Map<String, Object> effective) {
        Object value = effective.get(SerdeConfig.VALIDATION_ENABLED);
        return value != null && "false".equalsIgnoreCase(value.toString());
    }

    private static void assertRegistryUrlIsSet(Map<String, Object> effective, String name, String topicPath,
                                               String registryLabel) {
        if (!isSet(stringValue(effective, SchemaResolverConfig.REGISTRY_URL))) {
            throw new InvalidConfigurationException("No Apicurio Registry endpoint is configured for topic '" + name
                    + "': set " + SchemaResolverConfig.REGISTRY_URL + " in the properties of " + registryLabel
                    + ", or in " + topicPath + ".properties.");
        }
    }

    private static void assertHeadersAreEnabled(Map<String, Object> effective, String name, String topicPath) {
        if (!ApicurioSchemaValidatorFactory.areHeadersEnabled(effective)) {
            throw new InvalidConfigurationException(KafkaSerdeConfig.ENABLE_HEADERS + " is "
                    + stringValue(effective, KafkaSerdeConfig.ENABLE_HEADERS) + " for topic '" + name
                    + "', but Reactive Commons always writes the schema coordinates in the record headers: "
                    + "they are the only channel it has to tell the consumer which schema version a record was "
                    + "published with. Remove that property from " + topicPath + ".properties, or set it to true.");
        }
    }

    /**
     * Rejects an {@code apicurio.registry.artifact-resolver-strategy} Reactive Commons cannot honour.
     */
    private static void assertResolverStrategyIsRecognised(Map<String, Object> effective, String name,
                                                           String topicPath) {
        String strategy = stringValue(effective, SchemaResolverConfig.ARTIFACT_RESOLVER_STRATEGY);
        if (ApicurioSchemaValidatorFactory.isResolverStrategyRecognised(strategy)) {
            return;
        }
        throw new InvalidConfigurationException(SchemaResolverConfig.ARTIFACT_RESOLVER_STRATEGY + " is set to "
                + strategy + " for topic '" + name + "', but Reactive Commons only recognises "
                + ApicurioSchemaValidatorFactory.TOPIC_ID_STRATEGY + " and "
                + ApicurioSchemaValidatorFactory.SIMPLE_TOPIC_ID_STRATEGY + ": any other strategy is read by "
                + "Apicurio only when a Kafka record is handed to its serdes, which never happens here, so it "
                + "would be instantiated and never invoked. Set " + topicPath + ".properties."
                + SchemaResolverConfig.EXPLICIT_ARTIFACT_ID + " to a fixed artifact id instead.");
    }

    /**
     * Rejects a topic that leaves the schema version to chance: {@code apicurio.registry.find-latest} keeps its
     * Apicurio default of {@code false}, so either the version is pinned or the latest one is opted into.
     */
    private static void assertVersionIsResolvable(Map<String, Object> effective, String name, String topicPath) {
        if (!ApicurioSchemaValidatorFactory.isVersionResolvable(effective)) {
            throw new InvalidConfigurationException("No schema version could be resolved for topic '" + name + "': "
                    + SchemaResolverConfig.EXPLICIT_ARTIFACT_VERSION + " is empty and "
                    + SchemaResolverConfig.FIND_LATEST_ARTIFACT + " is false, which is its default in Apicurio. Set "
                    + SchemaResolverConfig.EXPLICIT_ARTIFACT_VERSION + " to pin the topic to a single version, or "
                    + "set " + SchemaResolverConfig.FIND_LATEST_ARTIFACT + "=true to validate against the latest "
                    + "one, either in the registry properties or in " + topicPath + ".properties.");
        }
    }

    private static String labelOf(String name, String path) {
        return isSet(name) ? "registry '" + name + "' (" + path + ")" : path;
    }

    private static String stringValue(Map<String, Object> configs, String key) {
        Object value = configs.get(key);
        return value == null ? null : value.toString();
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
