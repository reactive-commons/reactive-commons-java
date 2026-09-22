package org.reactivecommons.async.starter.impl.common.kafka.apicurio;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.apicurio.registry.resolver.ParsedSchemaImpl;
import io.apicurio.registry.resolver.SchemaLookupResult;
import io.apicurio.registry.resolver.SchemaResolver;
import io.apicurio.registry.resolver.strategy.ArtifactReference;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.reactivecommons.async.kafka.apicurio.SharedSchemaResolvers;
import org.reactivecommons.async.kafka.apicurio.TopicSchemaValidatorRouter;
import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.kafka.config.props.ApicurioRegistry;
import org.reactivecommons.async.kafka.config.props.ApicurioTopic;
import org.reactivecommons.async.kafka.config.props.ApicurioValidationProperties;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomainProperties;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.reactivecommons.async.starter.exceptions.InvalidConfigurationException;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the per topic validation of a domain, declared in code exactly as a {@code KafkaPropsCustomizer} would
 * build it: a topic left out of the declaration, topics pointing at different registries, topics reading different
 * groups of a single registry, and topics inheriting everything from their registry.
 */
@SuppressWarnings("unchecked")
class ApicurioTopicValidatorsTest {

    private static final String MAIN_URL = "http://localhost:8080/apis/registry/v3";
    private static final String SECONDARY_URL = "http://localhost:9090/apis/registry/v3";

    private static final String SCHEMA = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "name": { "type": "string" }
              },
              "required": ["name"]
            }
            """;

    private static final byte[] VALID = "{\"name\":\"john\"}".getBytes(StandardCharsets.UTF_8);

    private final List<Map<String, Object>> registryConfigs = new ArrayList<>();
    private final List<SchemaResolver<JsonSchema, Object>> createdResolvers = new ArrayList<>();

    private final SharedSchemaResolvers resolvers = new SharedSchemaResolvers(config -> {
        SchemaResolver<JsonSchema, Object> resolver = mock(SchemaResolver.class);
        lenient().when(resolver.resolveSchemaByArtifactReference(any())).thenReturn(lookup());
        registryConfigs.add(config);
        createdResolvers.add(resolver);
        return resolver;
    });

    private static SchemaLookupResult<JsonSchema> lookup() {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(SCHEMA);
        return SchemaLookupResult.<JsonSchema>builder()
                .parsedSchema(new ParsedSchemaImpl<JsonSchema>()
                        .setParsedSchema(schema)
                        .setRawSchema(SCHEMA.getBytes(StandardCharsets.UTF_8)))
                .groupId("kafka")
                .artifactId("artifact")
                .version("1")
                .build();
    }

    private static Map<String, String> registryProperties(String url) {
        Map<String, String> properties = new HashMap<>();
        properties.put("apicurio.registry.url", url);
        properties.put("apicurio.registry.artifact.group-id", "kafka");
        // The blank values a configuration file leaves declared but empty must behave as if they were absent
        properties.put("apicurio.registry.artifact.artifact-id", "");
        properties.put("apicurio.registry.artifact.version", "");
        properties.put("apicurio.registry.find-latest", "true");
        return properties;
    }

    private static ApicurioRegistry registry(String name, String url,
                                             ApicurioTopic... topics) {
        return ApicurioRegistry.builder()
                .name(name)
                .properties(registryProperties(url))
                .topics(new ArrayList<>(Arrays.asList(topics)))
                .build();
    }

    private static ApicurioTopic topic(String name) {
        return ApicurioTopic.builder().name(name).build();
    }

    private static ApicurioTopic topic(String name, String key, String value) {
        Map<String, String> properties = new HashMap<>();
        properties.put(key, value);
        return ApicurioTopic.builder().name(name).properties(properties).build();
    }

    private static List<ApicurioRegistry> declared(ApicurioRegistry... registries) {
        return new ArrayList<>(Arrays.asList(registries));
    }

    private TopicSchemaValidatorRouter router(List<ApicurioRegistry> registries) {
        return ApicurioTopicValidators.create(registries, "app", resolvers);
    }

    private ArtifactReference referenceUsedBy(TopicSchemaValidatorRouter router, String topic,
                                              SchemaResolver<JsonSchema, Object> resolver) {
        router.validateOutbound(topic, VALID, new RecordHeaders());
        ArgumentCaptor<ArtifactReference> captor = ArgumentCaptor.forClass(ArtifactReference.class);
        verify(resolver).resolveSchemaByArtifactReference(captor.capture());
        return captor.getValue();
    }

    // Scenario 1: three topics are produced and consumed, but 'push' is not declared, so it is not validated
    @Test
    void shouldLeaveUndeclaredTopicsUnvalidated() {
        TopicSchemaValidatorRouter router = router(declared(
                registry("main-registry", MAIN_URL,
                        topic("events-topic"),
                        topic("audit-topic", "apicurio.registry.artifact.artifact-id", "account"))));

        assertThat(router.validatedTopics()).containsOnlyKeys("events-topic", "audit-topic");
        assertThat(router.forTopic("push")).isSameAs(NoOpSchemaValidator.INSTANCE);

        // A record of an undeclared topic reaches no registry at all
        router.validateOutbound("push", "{}".getBytes(StandardCharsets.UTF_8), new RecordHeaders());
        verify(createdResolvers.get(0), never()).resolveSchemaByArtifactReference(any());
    }

    @Test
    void shouldInheritTheRegistryPropertiesAndFallBackToTheTopicNamedArtifact() {
        TopicSchemaValidatorRouter router = router(declared(
                registry("main-registry", MAIN_URL, topic("events-topic"))));

        ArtifactReference reference = referenceUsedBy(router, "events-topic", createdResolvers.get(0));

        assertThat(reference.getGroupId()).isEqualTo("kafka");
        assertThat(reference.getArtifactId()).isEqualTo("events-topic-value");
        assertThat(reference.getVersion()).isNull();
    }

    @Test
    void shouldLetATopicOverrideOnlyItsArtifactId() {
        TopicSchemaValidatorRouter router = router(declared(
                registry("main-registry", MAIN_URL,
                        topic("audit-topic", "apicurio.registry.artifact.artifact-id", "account"))));

        ArtifactReference reference = referenceUsedBy(router, "audit-topic", createdResolvers.get(0));

        assertThat(reference.getArtifactId()).isEqualTo("account");
        // The group is still the one of the registry
        assertThat(reference.getGroupId()).isEqualTo("kafka");
    }

    // Scenario 2: the topics are validated against different registries
    @Test
    void shouldUseADistinctConnectionPerRegistryEndpoint() {
        TopicSchemaValidatorRouter router = router(declared(
                registry("main-registry", MAIN_URL,
                        topic("events-topic"),
                        topic("audit-topic", "apicurio.registry.artifact.artifact-id", "account")),
                registry("secondary-registry", SECONDARY_URL, topic("push"))));

        assertThat(resolvers.count()).isEqualTo(2);
        assertThat(registryConfigs.get(0)).containsEntry("apicurio.registry.url", MAIN_URL);
        assertThat(registryConfigs.get(1)).containsEntry("apicurio.registry.url", SECONDARY_URL);

        router.validateOutbound("push", VALID, new RecordHeaders());
        verify(createdResolvers.get(1)).resolveSchemaByArtifactReference(any());
        verify(createdResolvers.get(0), never()).resolveSchemaByArtifactReference(any());
    }

    // Scenario 3: the topics read different groups of the same registry, which must share one connection
    @Test
    void shouldShareOneConnectionBetweenRegistriesWithTheSameEndpoint() {
        TopicSchemaValidatorRouter router = router(declared(
                registry("main-registry", MAIN_URL,
                        topic("events-topic"),
                        topic("audit-topic", "apicurio.registry.artifact.artifact-id", "account")),
                registry("secondary-registry", MAIN_URL,
                        topic("push", "apicurio.registry.artifact.group-id", "events"))));

        // Same endpoint and credentials, so a single client and a single schema cache serve every group
        assertThat(resolvers.count()).isOne();
        // The group is not part of the registry configuration, so it cannot split the connection
        assertThat(registryConfigs.get(0)).doesNotContainKey("apicurio.registry.artifact.group-id");

        assertThat(referenceUsedBy(router, "push", createdResolvers.get(0)).getGroupId()).isEqualTo("events");
    }

    // Scenario 4: every topic inherits the properties of its registry
    @Test
    void shouldValidateEveryDeclaredTopicWithTheRegistryProperties() {
        TopicSchemaValidatorRouter router = router(declared(
                registry("main-registry", MAIN_URL, topic("events-topic"), topic("audit-topic"))));

        assertThat(router.validatedTopics()).containsOnlyKeys("events-topic", "audit-topic");
        assertThat(resolvers.count()).isOne();
        assertThat(router.forTopic("events-topic")).isNotSameAs(router.forTopic("audit-topic"));

        router.validateOutbound("events-topic", VALID, new RecordHeaders());
        router.validateOutbound("audit-topic", VALID, new RecordHeaders());
        ArgumentCaptor<ArtifactReference> captor = ArgumentCaptor.forClass(ArtifactReference.class);
        verify(createdResolvers.get(0), times(2)).resolveSchemaByArtifactReference(captor.capture());
        assertThat(captor.getAllValues()).extracting(ArtifactReference::getArtifactId)
                .containsExactly("events-topic-value", "audit-topic-value");
    }

    @Test
    void shouldKeepATopicUnvalidatedWhenItDisablesTheValidation() {
        TopicSchemaValidatorRouter router = router(declared(
                registry("main-registry", MAIN_URL,
                        topic("events-topic"),
                        topic("push", "apicurio.registry.serde.validation-enabled", "false"))));

        assertThat(router.forTopic("push")).isSameAs(NoOpSchemaValidator.INSTANCE);
        assertThat(router.forTopic("events-topic")).isNotSameAs(NoOpSchemaValidator.INSTANCE);
    }

    @Test
    void shouldKeepEveryTopicUnvalidatedWhenTheRegistryDisablesTheValidation() {
        ApicurioRegistry registry = registry("main-registry", MAIN_URL,
                topic("events-topic"), topic("audit-topic"));
        registry.getProperties().put("apicurio.registry.serde.validation-enabled", "false");

        TopicSchemaValidatorRouter router = router(declared(registry));

        assertThat(router.forTopic("events-topic")).isSameAs(NoOpSchemaValidator.INSTANCE);
        assertThat(router.forTopic("audit-topic")).isSameAs(NoOpSchemaValidator.INSTANCE);
        assertThat(resolvers.count()).isZero();
    }

    @Test
    void shouldValidateBothDirectionsOfADeclaredTopic() {
        TopicSchemaValidatorRouter router = router(declared(
                registry("main-registry", MAIN_URL, topic("events-topic"))));

        router.validateOutbound("events-topic", VALID, new RecordHeaders());
        router.validateInbound("events-topic", VALID, new RecordHeaders());

        verify(createdResolvers.get(0), times(2)).resolveSchemaByArtifactReference(any());
    }

    @Test
    void shouldReturnNoValidatorWhenTheDomainDeclaresNoRegistry() {
        assertThat(ApicurioTopicValidators.buildValidators(null, "app", resolvers)).isEmpty();
        assertThat(ApicurioTopicValidators.buildValidators(List.of(), "app", resolvers)).isEmpty();
        assertThat(resolvers.count()).isZero();
    }

    @Test
    void shouldLetSeveralTopicsShareOneArtifact() {
        // A single contract for several topics: the registry names the artifact and no topic overrides it
        ApicurioRegistry registry = registry("main-registry", MAIN_URL,
                topic("events-topic"), topic("audit-topic"));
        registry.getProperties().put("apicurio.registry.artifact.artifact-id", "envelope");

        TopicSchemaValidatorRouter router = router(declared(registry));
        router.validateOutbound("events-topic", VALID, new RecordHeaders());
        router.validateOutbound("audit-topic", VALID, new RecordHeaders());

        ArgumentCaptor<ArtifactReference> captor = ArgumentCaptor.forClass(ArtifactReference.class);
        verify(createdResolvers.get(0), times(2)).resolveSchemaByArtifactReference(captor.capture());
        assertThat(captor.getAllValues()).extracting(ArtifactReference::getArtifactId)
                .containsExactly("envelope", "envelope");
    }

    @Test
    void shouldLetOneTopicPinAVersionWhileAnotherFollowsTheLatest() {
        TopicSchemaValidatorRouter router = router(declared(
                registry("main-registry", MAIN_URL,
                        topic("events-topic"),
                        topic("audit-topic", "apicurio.registry.artifact.version", "2"))));

        // One connection, because the version is not part of the registry configuration
        assertThat(resolvers.count()).isOne();
        router.validateOutbound("events-topic", VALID, new RecordHeaders());
        router.validateOutbound("audit-topic", VALID, new RecordHeaders());

        ArgumentCaptor<ArtifactReference> captor = ArgumentCaptor.forClass(ArtifactReference.class);
        verify(createdResolvers.get(0), times(2)).resolveSchemaByArtifactReference(captor.capture());
        assertThat(captor.getAllValues()).extracting(ArtifactReference::getVersion)
                .containsExactly(null, "2");
    }

    @Test
    void shouldResolveTheDefaultGroupWhenTheRegistryDeclaresNone() {
        ApicurioRegistry registry = registry("main-registry", MAIN_URL, topic("events-topic"));
        registry.getProperties().remove("apicurio.registry.artifact.group-id");

        ArtifactReference reference = referenceUsedBy(router(declared(registry)), "events-topic",
                createdResolvers.get(0));

        // Apicurio's ArtifactReference.build() turns a missing group into the literal "default" group
        assertThat(reference.getGroupId()).isEqualTo("default");
    }

    @Test
    void shouldLetOneDomainValidateATopicWhileAnotherSkipsIt() {
        // The producer's domain validates the topic, the consumer's one does not declare it
        Map<String, SchemaValidator> app = ApicurioTopicValidators.buildValidators(
                declared(registry("main-registry", MAIN_URL, topic("events-topic"))), "app", resolvers);
        Map<String, SchemaValidator> reporting = ApicurioTopicValidators.buildValidators(
                declared(registry("main-registry", MAIN_URL, topic("audit-topic"))), "reporting", resolvers);

        assertThat(app).containsOnlyKeys("events-topic");
        assertThat(reporting).containsOnlyKeys("audit-topic");
        // One endpoint, one connection, whatever domain declares it
        assertThat(resolvers.count()).isOne();
    }

    @Test
    void shouldNotValidateTheDlqTopicUnlessItIsDeclared() {
        // A discarded message is republished as <topic>.dlq, which is another topic
        TopicSchemaValidatorRouter router = router(declared(
                registry("main-registry", MAIN_URL, topic("events-topic"))));

        router.validateOutbound("events-topic.dlq", VALID, new RecordHeaders());

        assertThat(router.forTopic("events-topic.dlq")).isSameAs(NoOpSchemaValidator.INSTANCE);
        verify(createdResolvers.get(0), never()).resolveSchemaByArtifactReference(any());
    }

    @Test
    void shouldValidateTheDlqTopicWhenItIsDeclared() {
        TopicSchemaValidatorRouter router = router(declared(
                registry("main-registry", MAIN_URL,
                        topic("events-topic"),
                        topic("events-topic.dlq", "apicurio.registry.artifact.artifact-id", "envelope-dlq"))));

        assertThat(referenceUsedBy(router, "events-topic.dlq", createdResolvers.get(0)).getArtifactId())
                .isEqualTo("envelope-dlq");
    }

    @Test
    void shouldRejectATopicDeclaredTwiceInTheSameDomain() {
        List<ApicurioRegistry> registries = declared(
                registry("main-registry", MAIN_URL, topic("audit-topic")),
                registry("secondary-registry", SECONDARY_URL, topic("audit-topic")));

        assertThatThrownBy(() -> router(registries))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("Topic 'audit-topic' of domain app is declared by registry "
                        + "'main-registry' (reactive.commons.kafka.app.apicurio.registries[0])")
                .hasMessageContaining("registry 'secondary-registry' "
                        + "(reactive.commons.kafka.app.apicurio.registries[1])")
                .hasMessageContaining("The same topic name may be declared by another domain");
    }

    @Test
    void shouldAcceptTheSameTopicNameInAnotherDomain() {
        Map<String, SchemaValidator> app = ApicurioTopicValidators.buildValidators(
                declared(registry("main-registry", MAIN_URL, topic("audit-topic"))), "app", resolvers);
        Map<String, SchemaValidator> accounts = ApicurioTopicValidators.buildValidators(
                declared(registry("accounts-registry", SECONDARY_URL, topic("audit-topic"))), "accounts",
                resolvers);

        assertThat(app).containsOnlyKeys("audit-topic");
        assertThat(accounts).containsOnlyKeys("audit-topic");
        assertThat(app.get("audit-topic")).isNotSameAs(accounts.get("audit-topic"));
        // Different endpoints, so one connection each
        assertThat(resolvers.count()).isEqualTo(2);
    }

    @Test
    void shouldShareOneConnectionBetweenDomainsWithTheSameRegistry() {
        ApicurioTopicValidators.buildValidators(
                declared(registry("main-registry", MAIN_URL, topic("events-topic"))), "app", resolvers);
        ApicurioTopicValidators.buildValidators(
                declared(registry("accounts-registry", MAIN_URL, topic("audit-topic"))), "accounts",
                resolvers);

        assertThat(resolvers.count()).isOne();
    }

    @Test
    void shouldRejectARegistryWithoutTopics() {
        List<ApicurioRegistry> registries = declared(registry("main-registry", MAIN_URL));

        assertThatThrownBy(() -> router(registries))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("declares no topics")
                .hasMessageContaining("reactive.commons.kafka.app.apicurio.registries[0].topics");
    }

    @Test
    void shouldRejectATopicWithoutName() {
        List<ApicurioRegistry> registries = declared(
                registry("main-registry", MAIN_URL, ApicurioTopic.builder().build()));

        assertThatThrownBy(() -> router(registries))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("reactive.commons.kafka.app.apicurio.registries[0].topics[0].name");
    }

    @Test
    void shouldRejectATopicWithoutRegistryUrl() {
        ApicurioRegistry registry = registry("main-registry", MAIN_URL, topic("events-topic"));
        registry.getProperties().remove("apicurio.registry.url");

        assertThatThrownBy(() -> router(declared(registry)))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("No Apicurio Registry endpoint is configured for topic 'events-topic'");
    }

    @Test
    void shouldRejectATopicWithNoResolvableVersion() {
        ApicurioRegistry registry = registry("main-registry", MAIN_URL, topic("events-topic"));
        registry.getProperties().put("apicurio.registry.find-latest", "false");

        assertThatThrownBy(() -> router(declared(registry)))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("No schema version could be resolved for topic 'events-topic'")
                .hasMessageContaining("apicurio.registry.find-latest=true");
    }

    @Test
    void shouldAcceptAPinnedVersionInsteadOfTheLatestOne() {
        ApicurioRegistry registry = registry("main-registry", MAIN_URL,
                topic("events-topic", "apicurio.registry.artifact.version", "1"));
        registry.getProperties().put("apicurio.registry.find-latest", "false");

        TopicSchemaValidatorRouter router = router(declared(registry));

        assertThat(referenceUsedBy(router, "events-topic", createdResolvers.get(0)).getVersion()).isEqualTo("1");
    }

    @Test
    void shouldRejectATopicThatDisablesTheApicurioHeaders() {
        List<ApicurioRegistry> registries = declared(
                registry("main-registry", MAIN_URL,
                        topic("events-topic", "apicurio.registry.headers.enabled", "false")));

        assertThatThrownBy(() -> router(registries))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("apicurio.registry.headers.enabled is false for topic 'events-topic'");
    }

    @Test
    void shouldResolveTheArtifactIdFromTheTopicNameWithSimpleTopicIdStrategy() {
        List<ApicurioRegistry> registries = declared(
                registry("main-registry", MAIN_URL,
                        topic("events-topic", "apicurio.registry.artifact-resolver-strategy",
                                "io.apicurio.registry.serde.strategy.SimpleTopicIdStrategy")));

        TopicSchemaValidatorRouter router = router(registries);

        assertThat(referenceUsedBy(router, "events-topic", createdResolvers.get(0)).getArtifactId())
                .isEqualTo("events-topic");
    }

    @Test
    void shouldResolveTheArtifactIdFromTheTopicNameWithTopicIdStrategy() {
        List<ApicurioRegistry> registries = declared(
                registry("main-registry", MAIN_URL,
                        topic("events-topic", "apicurio.registry.artifact-resolver-strategy",
                                "io.apicurio.registry.serde.strategy.TopicIdStrategy")));

        TopicSchemaValidatorRouter router = router(registries);

        assertThat(referenceUsedBy(router, "events-topic", createdResolvers.get(0)).getArtifactId())
                .isEqualTo("events-topic-value");
    }

    @Test
    void shouldRejectATopicWithAnUnrecognisedArtifactResolverStrategy() {
        List<ApicurioRegistry> registries = declared(
                registry("main-registry", MAIN_URL,
                        topic("events-topic", "apicurio.registry.artifact-resolver-strategy",
                                "io.apicurio.registry.serde.strategy.RecordIdStrategy")));

        assertThatThrownBy(() -> router(registries))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("apicurio.registry.artifact-resolver-strategy is set to")
                .hasMessageContaining("instantiated and never invoked");
    }

    @Test
    void shouldRejectAnEmptyRegistryDeclaration() {
        List<ApicurioRegistry> registries = new ArrayList<>();
        registries.add(null);

        assertThatThrownBy(() -> router(registries))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("reactive.commons.kafka.app.apicurio.registries[0] is empty");
    }

    @Test
    void shouldReportTheIndexOfAnUnnamedRegistry() {
        assertThatThrownBy(() -> router(declared(registry(null, MAIN_URL))))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("reactive.commons.kafka.app.apicurio.registries[0] declares no topics");
    }

    @Test
    void shouldTrimTheDeclaredTopicName() {
        assertThat(router(declared(registry("main-registry", MAIN_URL, topic("  events-topic  "))))
                .validatedTopics()).containsOnlyKeys("events-topic");
    }

    /**
     * The whole declaration written in code, which is what a {@code KafkaPropsCustomizer} bean does with the
     * properties bound from the configuration files.
     */
    @Test
    void shouldAcceptTheWholeDeclarationBuiltByACustomizer() {
        AsyncKafkaPropsDomainProperties configured = new AsyncKafkaPropsDomainProperties();
        configured.put("app", AsyncKafkaProps.builder().build());

        AsyncKafkaPropsDomain.KafkaPropsCustomizer customizer = domainProperties ->
                domainProperties.get("app").setApicurio(ApicurioValidationProperties.builder()
                        .registries(List.of(ApicurioRegistry.builder()
                                .name("main-registry")
                                .properties(new HashMap<>(Map.of(
                                        "apicurio.registry.url", MAIN_URL,
                                        "apicurio.registry.artifact.group-id", "kafka",
                                        "apicurio.registry.find-latest", "true")))
                                .topics(List.of(
                                        topic("events-topic"),
                                        topic("audit-topic",
                                                "apicurio.registry.artifact.artifact-id", "account")))
                                .build()))
                        .build());

        ObjectProvider<AsyncKafkaPropsDomain.KafkaPropsCustomizer> customizerProvider = mock(ObjectProvider.class);
        when(customizerProvider.getIfAvailable()).thenReturn(customizer);
        AsyncKafkaPropsDomain domains = new AsyncKafkaPropsDomain("test-app", new KafkaProperties(), configured,
                (ignoredDomain, ignoredProps) -> {
                }, customizerProvider);

        Map<String, SchemaValidator> validators = RCKafkaApicurioConfig.buildValidators(domains, resolvers);

        assertThat(validators).containsOnlyKeys("app");
        TopicSchemaValidatorRouter router = (TopicSchemaValidatorRouter) validators.get("app");
        assertThat(router.validatedTopics()).containsOnlyKeys("events-topic", "audit-topic");
        assertThat(router.forTopic("push")).isSameAs(NoOpSchemaValidator.INSTANCE);
        assertThat(referenceUsedBy(router, "audit-topic", createdResolvers.get(0)).getArtifactId())
                .isEqualTo("account");
    }
}

