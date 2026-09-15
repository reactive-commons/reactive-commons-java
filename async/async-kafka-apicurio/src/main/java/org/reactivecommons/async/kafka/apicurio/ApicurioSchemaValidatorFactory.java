package org.reactivecommons.async.kafka.apicurio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import io.apicurio.registry.resolver.DefaultSchemaResolver;
import io.apicurio.registry.resolver.SchemaResolver;
import io.apicurio.registry.resolver.config.SchemaResolverConfig;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.apicurio.registry.serde.kafka.config.KafkaSerdeConfig;
import io.apicurio.registry.serde.kafka.headers.DefaultHeadersHandler;
import io.apicurio.registry.serde.kafka.headers.HeadersHandler;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds an {@link ApicurioSchemaValidator} from the very same configuration keys used by the
 * Apicurio Kafka serdes ({@link SerdeConfig}), so an existing configuration can be reused as is.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApicurioSchemaValidatorFactory {

    /**
     * Lifetime of a cached schema. A registered version is immutable, so the only thing this period delays is
     * noticing that the latest version of an artifact has changed.
     */
    static final long CHECK_PERIOD_MS_DEFAULT = Duration.ofMinutes(30).toMillis();

    /**
     * Keys that select which artifact of the registry is validated, as opposed to which registry is contacted.
     * They are resolved per record, so two validators differing only in these still share one connection and one
     * schema cache.
     */
    public static final Set<String> ARTIFACT_KEYS = Set.of(
            SchemaResolverConfig.EXPLICIT_ARTIFACT_GROUP_ID,
            SchemaResolverConfig.EXPLICIT_ARTIFACT_ID,
            SchemaResolverConfig.EXPLICIT_ARTIFACT_VERSION,
            SchemaResolverConfig.FIND_LATEST_ARTIFACT,
            SchemaResolverConfig.ARTIFACT_RESOLVER_STRATEGY);

    /**
     * Fully qualified name of Apicurio's {@code TopicIdStrategy}. It resolves the
     * artifact as {@code <topic>-value}, which is already {@link DefaultArtifactReferenceProvider}'s default when
     * no artifact id is configured, so setting it explicitly changes nothing.
     */
    public static final String TOPIC_ID_STRATEGY =
            "io.apicurio.registry.serde.strategy.TopicIdStrategy";

    /**
     * Fully qualified name of Apicurio's {@code SimpleTopicIdStrategy}. It resolves the artifact as the topic name
     * itself, with no suffix.
     */
    public static final String SIMPLE_TOPIC_ID_STRATEGY =
            "io.apicurio.registry.serde.strategy.SimpleTopicIdStrategy";

    /**
     * Reduces a configuration to what the registry client and the schema cache depend on: endpoint, credentials,
     * TLS and tuning. Two configurations with the same result may share a single resolver.
     *
     * @param configs the full configuration of a domain or a topic
     * @return the registry level configuration, without the artifact coordinates
     */
    public static Map<String, Object> registryConfig(Map<String, Object> configs) {
        Map<String, Object> registryConfig = new HashMap<>(configs);
        registryConfig.keySet().removeAll(ARTIFACT_KEYS);
        return registryConfig;
    }

    public static ApicurioSchemaValidator create(Map<String, Object> configs) {
        return create(configs, null);
    }

    public static ApicurioSchemaValidator create(Map<String, Object> configs, ObjectMapper objectMapper) {
        Prepared prepared = prepare(configs);
        return build(newResolver(prepared.resolved()), true, prepared, objectMapper);
    }

    /**
     * Builds a validator on top of a resolver created with {@link #createResolver(Map)}.
     * <p>
     * The resolver holds the registry client and the schema cache, which only depend on the registry itself, while
     * the artifact coordinates and the directions to validate are read from {@code configs} for this validator
     * alone. That is what lets several domains resolving against the same registry share one connection and one
     * cache while keeping their own group, artifact and switches: the cache is indexed by the full coordinates,
     * so the entries of one group never collide with those of another.
     * <p>
     * The resolver is <b>not</b> owned by the returned validator, so closing it stays the responsibility of
     * whoever created it.
     */
    public static ApicurioSchemaValidator create(SchemaResolver<JsonSchema, Object> schemaResolver,
                                                 Map<String, Object> configs, ObjectMapper objectMapper) {
        return build(schemaResolver, false, prepare(configs), objectMapper);
    }

    /**
     * Creates the registry client and the schema cache shared by every validator of the same registry.
     * <p>
     * Only the registry level keys are read: endpoint, credentials, TLS and cache tuning. The artifact
     * coordinates are ignored here because they are resolved per record by the validator.
     *
     * @return a resolver the caller owns, and must close when it is no longer used
     */
    public static SchemaResolver<JsonSchema, Object> createResolver(Map<String, Object> configs) {
        return newResolver(prepare(configs).resolved());
    }

    /**
     * The configuration actually handed to Apicurio, plus the artifact id convention read out of it.
     * <p>
     * {@code apicurio.registry.artifact-resolver-strategy} is removed from {@code resolved} before it ever reaches
     * {@code DefaultSchemaResolver#configure}: that call instantiates whatever class the key names, even though
     * {@link ApicurioSchemaValidator} never invokes it, so a class Reactive Commons does not itself recognise would
     * otherwise fail with a raw {@code ClassNotFoundException} instead of the clear message
     * {@link #resolveIdStrategy} produces.
     */
    private record Prepared(Map<String, Object> resolved, ArtifactIdStrategy idStrategy) {
    }

    private static Prepared prepare(Map<String, Object> configs) {
        Map<String, Object> resolved = new HashMap<>(configs);
        assertHeadersAreEnabled(resolved);
        ArtifactIdStrategy idStrategy = resolveIdStrategy(resolved);
        resolved.remove(SchemaResolverConfig.ARTIFACT_RESOLVER_STRATEGY);
        applyResolverDefaults(resolved);
        return new Prepared(resolved, idStrategy);
    }

    private static SchemaResolver<JsonSchema, Object> newResolver(Map<String, Object> resolved) {
        SchemaResolver<JsonSchema, Object> schemaResolver = new DefaultSchemaResolver<>();
        schemaResolver.configure(resolved, new RestrictedJsonSchemaParser<>());
        return schemaResolver;
    }

    private static ApicurioSchemaValidator build(SchemaResolver<JsonSchema, Object> schemaResolver,
                                                 boolean ownsResolver, Prepared prepared,
                                                 ObjectMapper objectMapper) {
        Map<String, Object> resolved = prepared.resolved();
        // Checked here and not while preparing the resolver: the artifact coordinates belong to the validator, a
        // shared resolver is built without them
        assertVersionIsResolvable(resolved);

        HeadersHandler headersHandler = new DefaultHeadersHandler();
        headersHandler.configure(resolved, false);

        boolean validationEnabled = booleanValue(resolved, SerdeConfig.VALIDATION_ENABLED,
                SerdeConfig.VALIDATION_ENABLED_DEFAULT);
        if (!validationEnabled) {
            throw new IllegalArgumentException(SerdeConfig.VALIDATION_ENABLED + " is false, so this validator would "
                    + "connect to the Apicurio Registry and resolve schemas without validating any message. Remove "
                    + "that property to keep validating, or do not create the validator at all.");
        }

        return ApicurioSchemaValidator.builder()
                .schemaResolver(schemaResolver)
                .ownsResolver(ownsResolver)
                .headersHandler(headersHandler)
                .artifactReferenceProvider(new DefaultArtifactReferenceProvider(
                        stringValue(resolved, SchemaResolverConfig.EXPLICIT_ARTIFACT_GROUP_ID),
                        stringValue(resolved, SchemaResolverConfig.EXPLICIT_ARTIFACT_ID),
                        stringValue(resolved, SchemaResolverConfig.EXPLICIT_ARTIFACT_VERSION),
                        prepared.idStrategy()))
                .objectMapper(objectMapper)
                .build();
    }

    /**
     * Resolves which convention derives the artifact id from the topic name, read from
     * {@code apicurio.registry.artifact-resolver-strategy}, the same key the Apicurio Kafka serdes accept.
     * <p>
     * {@code apicurio.registry.artifact.artifact-id} takes precedence when it is set, since it names one fixed
     * artifact for every topic instead of a convention.
     */
    private static ArtifactIdStrategy resolveIdStrategy(Map<String, Object> resolved) {
        if (isSet(stringValue(resolved, SchemaResolverConfig.EXPLICIT_ARTIFACT_ID))) {
            return ArtifactIdStrategy.TOPIC_ID;
        }
        String strategy = stringValue(resolved, SchemaResolverConfig.ARTIFACT_RESOLVER_STRATEGY);
        if (!isSet(strategy) || TOPIC_ID_STRATEGY.equals(strategy)) {
            return ArtifactIdStrategy.TOPIC_ID;
        }
        if (SIMPLE_TOPIC_ID_STRATEGY.equals(strategy)) {
            return ArtifactIdStrategy.SIMPLE_TOPIC_ID;
        }
        throw new IllegalArgumentException(SchemaResolverConfig.ARTIFACT_RESOLVER_STRATEGY + " is set to " + strategy
                + ", but Reactive Commons only recognises " + TOPIC_ID_STRATEGY + " and " + SIMPLE_TOPIC_ID_STRATEGY
                + ": any other strategy is read by Apicurio only when a Kafka record is handed to its serdes, which "
                + "never happens here, so it would be instantiated and never invoked. Set "
                + SchemaResolverConfig.EXPLICIT_ARTIFACT_ID + " to a fixed artifact id instead.");
    }

    private static void assertVersionIsResolvable(Map<String, Object> resolved) {
        boolean findLatest = booleanValue(resolved, SchemaResolverConfig.FIND_LATEST_ARTIFACT,
                SchemaResolverConfig.FIND_LATEST_ARTIFACT_DEFAULT);
        if (!findLatest && !isSet(stringValue(resolved, SchemaResolverConfig.EXPLICIT_ARTIFACT_VERSION))) {
            throw new IllegalArgumentException("No schema version could be resolved: "
                    + SchemaResolverConfig.EXPLICIT_ARTIFACT_VERSION + " is not set and "
                    + SchemaResolverConfig.FIND_LATEST_ARTIFACT + " is false, which is its default. Set the version, "
                    + "or set " + SchemaResolverConfig.FIND_LATEST_ARTIFACT + "=true to resolve the latest one.");
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Applies schema resolver defaults better suited for reactive workloads:
     * reduces refresh frequency and keeps cached schemas when refreshes fail.
     * Both settings can still be overridden via {@code properties}.
     */
    static void applyResolverDefaults(Map<String, Object> configs) {
        configs.putIfAbsent(SchemaResolverConfig.CHECK_PERIOD_MS, CHECK_PERIOD_MS_DEFAULT);
        configs.putIfAbsent(SchemaResolverConfig.FAULT_TOLERANT_REFRESH, true);
    }

    private static void assertHeadersAreEnabled(Map<String, Object> configs) {
        if (!booleanValue(configs, KafkaSerdeConfig.ENABLE_HEADERS, true)) {
            throw new IllegalArgumentException(KafkaSerdeConfig.ENABLE_HEADERS + " is false, but Reactive Commons "
                    + "always writes the schema coordinates in the record headers: they are the only channel it has "
                    + "to tell the consumer which schema version a record was published with. Remove that property "
                    + "or set " + KafkaSerdeConfig.ENABLE_HEADERS + "=true.");
        }
        configs.put(KafkaSerdeConfig.ENABLE_HEADERS, true);
    }

    private static String stringValue(Map<String, Object> configs, String key) {
        Object value = configs.get(key);
        return value == null ? null : value.toString();
    }

    private static boolean booleanValue(Map<String, Object> configs, String key, boolean defaultValue) {
        Object value = configs.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
    }
}
