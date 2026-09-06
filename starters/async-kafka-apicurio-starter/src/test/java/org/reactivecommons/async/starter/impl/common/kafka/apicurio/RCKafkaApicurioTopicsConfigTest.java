package org.reactivecommons.async.starter.impl.common.kafka.apicurio;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.kafka.apicurio.ApicurioSchemaValidator;
import org.reactivecommons.async.kafka.apicurio.TopicSchemaValidatorRouter;
import org.reactivecommons.async.kafka.config.props.ApicurioRegistryDefinition;
import org.reactivecommons.async.kafka.config.props.ApicurioTopicDefinition;
import org.reactivecommons.async.kafka.config.props.ApicurioValidationProperties;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.reactivecommons.async.kafka.validation.DomainSchemaValidatorProvider;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.starter.exceptions.InvalidConfigurationException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Covers the Apicurio configuration segmented per topic as it is declared in the configuration files, under
 * {@code reactive.commons.kafka.<domain>.apicurio.registries}.
 */
class RCKafkaApicurioTopicsConfigTest {

    private static final String MAIN_URL = "http://localhost:8080/apis/registry/v3";
    private static final String SECONDARY_URL = "http://localhost:9090/apis/registry/v3";
    private static final String APP_REGISTRIES = "reactive.commons.kafka.app.apicurio.registries";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RCKafkaApicurioConfigTest.DomainPropertiesConfig.class)
            .withConfiguration(AutoConfigurations.of(RCKafkaApicurioConfig.class))
            // The default domain has to exist, as it does in any Reactive Commons application
            .withPropertyValues("reactive.commons.kafka.app.connection-properties.bootstrap-servers=broker-a:9092");

    /**
     * The declaration of the first scenarios: 'events-topic' inherits everything, 'audit-topic' overrides its
     * artifact id, and 'push' is deliberately left out so it is not validated.
     */
    private ApplicationContextRunner withMainRegistry() {
        return runner.withPropertyValues(
                APP_REGISTRIES + "[0].name=main-registry",
                APP_REGISTRIES + "[0].properties.apicurio\\.registry\\.url=" + MAIN_URL,
                APP_REGISTRIES + "[0].properties.apicurio\\.registry\\.artifact\\.group-id=kafka",
                APP_REGISTRIES + "[0].properties.apicurio\\.registry\\.find-latest=true",
                APP_REGISTRIES + "[0].topics[0].name=events-topic",
                APP_REGISTRIES + "[0].topics[1].name=audit-topic",
                APP_REGISTRIES + "[0].topics[1].properties."
                        + "apicurio\\.registry\\.artifact\\.artifact-id=account");
    }

    @Test
    void shouldBindTheRegistriesAndTheirTopics() {
        withMainRegistry().run(context -> {
            ApicurioValidationProperties properties =
                    context.getBean(AsyncKafkaPropsDomain.class).getProps("app").getApicurio();

            assertThat(properties.getRegistries()).hasSize(1);
            ApicurioRegistryDefinition registry = properties.getRegistries().get(0);
            assertThat(registry.getName()).isEqualTo("main-registry");
            assertThat(registry.getProperties())
                    .containsEntry("apicurio.registry.url", MAIN_URL)
                    .containsEntry("apicurio.registry.artifact.group-id", "kafka")
                    .containsEntry("apicurio.registry.find-latest", "true");
            assertThat(registry.getTopics()).extracting(ApicurioTopicDefinition::getName)
                    .containsExactly("events-topic", "audit-topic");
            assertThat(registry.getTopics().get(0).getProperties()).isEmpty();
            assertThat(registry.getTopics().get(1).getProperties())
                    .containsEntry("apicurio.registry.artifact.artifact-id", "account");
        });
    }

    // Scenario 1: 'push' is produced and consumed but not declared, so it is not validated
    @Test
    void shouldValidateOnlyTheDeclaredTopics() {
        withMainRegistry().run(context -> {
            TopicSchemaValidatorRouter router = router(context.getBean(DomainSchemaValidatorProvider.class), "app");

            assertThat(router.validatedTopics()).containsOnlyKeys("events-topic", "audit-topic");
            assertThat(router.forTopic("events-topic")).isInstanceOf(ApicurioSchemaValidator.class);
            assertThat(router.forTopic("push")).isSameAs(NoOpSchemaValidator.INSTANCE);
        });
    }

    // Scenario 2: the topics are validated against different registries
    @Test
    void shouldSupportTopicsPointingAtDifferentRegistries() {
        withMainRegistry().withPropertyValues(
                        APP_REGISTRIES + "[1].name=secondary-registry",
                        APP_REGISTRIES + "[1].properties.apicurio\\.registry\\.url=" + SECONDARY_URL,
                        APP_REGISTRIES + "[1].properties.apicurio\\.registry\\.artifact\\.group-id=kafka",
                        APP_REGISTRIES + "[1].properties.apicurio\\.registry\\.find-latest=true",
                        APP_REGISTRIES + "[1].topics[0].name=push")
                .run(context -> {
                    TopicSchemaValidatorRouter router =
                            router(context.getBean(DomainSchemaValidatorProvider.class), "app");

                    assertThat(router.validatedTopics())
                            .containsOnlyKeys("events-topic", "audit-topic", "push");
                    assertThat(router.forTopic("push")).isInstanceOf(ApicurioSchemaValidator.class)
                            .isNotSameAs(router.forTopic("events-topic"));
                });
    }

    // Scenario 3: the topics read different groups of the same registry
    @Test
    void shouldSupportTopicsReadingDifferentGroupsOfTheSameRegistry() {
        withMainRegistry().withPropertyValues(
                        APP_REGISTRIES + "[1].name=secondary-registry",
                        APP_REGISTRIES + "[1].properties.apicurio\\.registry\\.url=" + MAIN_URL,
                        APP_REGISTRIES + "[1].properties.apicurio\\.registry\\.artifact\\.group-id=kafka",
                        APP_REGISTRIES + "[1].properties.apicurio\\.registry\\.find-latest=true",
                        APP_REGISTRIES + "[1].topics[0].name=push",
                        APP_REGISTRIES + "[1].topics[0].properties."
                                + "apicurio\\.registry\\.artifact\\.group-id=events")
                .run(context -> {
                    ApicurioValidationProperties properties =
                            context.getBean(AsyncKafkaPropsDomain.class).getProps("app").getApicurio();
                    assertThat(properties.getRegistries().get(1).getTopics().get(0).getProperties())
                            .containsEntry("apicurio.registry.artifact.group-id", "events");

                    assertThat(router(context.getBean(DomainSchemaValidatorProvider.class), "app").validatedTopics())
                            .containsOnlyKeys("events-topic", "audit-topic", "push");
                });
    }

    // Scenario 4: every declared topic inherits the properties of its registry
    @Test
    void shouldLetEveryTopicInheritTheRegistryProperties() {
        runner.withPropertyValues(
                        APP_REGISTRIES + "[0].name=main-registry",
                        APP_REGISTRIES + "[0].properties.apicurio\\.registry\\.url=" + MAIN_URL,
                        APP_REGISTRIES + "[0].properties.apicurio\\.registry\\.artifact\\.group-id=kafka",
                        APP_REGISTRIES + "[0].properties.apicurio\\.registry\\.find-latest=true",
                        APP_REGISTRIES + "[0].topics[0].name=events-topic",
                        APP_REGISTRIES + "[0].topics[1].name=audit-topic")
                .run(context -> {
                    TopicSchemaValidatorRouter router =
                            router(context.getBean(DomainSchemaValidatorProvider.class), "app");

                    assertThat(router.validatedTopics()).containsOnlyKeys("events-topic", "audit-topic");
                    assertThat(router.forTopic("events-topic"))
                            .isInstanceOf(ApicurioSchemaValidator.class)
                            .isNotSameAs(router.forTopic("audit-topic"));
                });
    }

    @Test
    void shouldGiveEachDomainItsOwnRouter() {
        withMainRegistry().withPropertyValues(
                        "reactive.commons.kafka.accounts.connection-properties.bootstrap-servers=broker-b:9092",
                        "reactive.commons.kafka.accounts.apicurio.registries[0].name=accounts-registry",
                        "reactive.commons.kafka.accounts.apicurio.registries[0].properties."
                                + "apicurio\\.registry\\.url=" + SECONDARY_URL,
                        "reactive.commons.kafka.accounts.apicurio.registries[0].properties."
                                + "apicurio\\.registry\\.find-latest=true",
                        // The very same topic name as the 'app' domain, validated against another registry
                        "reactive.commons.kafka.accounts.apicurio.registries[0].topics[0].name=audit-topic")
                .run(context -> {
                    DomainSchemaValidatorProvider provider = context.getBean(DomainSchemaValidatorProvider.class);
                    TopicSchemaValidatorRouter app = router(provider, "app");
                    TopicSchemaValidatorRouter accounts = router(provider, "accounts");

                    assertThat(accounts).isNotSameAs(app);
                    assertThat(accounts.validatedTopics()).containsOnlyKeys("audit-topic");
                    assertThat(accounts.forTopic("audit-topic")).isInstanceOf(ApicurioSchemaValidator.class)
                            .isNotSameAs(app.forTopic("audit-topic"));
                    assertThat(accounts.forTopic("events-topic")).isSameAs(NoOpSchemaValidator.INSTANCE);
                });
    }

    @Test
    void shouldRejectATopicDeclaredTwiceInTheSameDomain() {
        withMainRegistry().withPropertyValues(
                        APP_REGISTRIES + "[1].name=secondary-registry",
                        APP_REGISTRIES + "[1].properties.apicurio\\.registry\\.url=" + SECONDARY_URL,
                        APP_REGISTRIES + "[1].properties.apicurio\\.registry\\.find-latest=true",
                        APP_REGISTRIES + "[1].topics[0].name=audit-topic")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(InvalidConfigurationException.class)
                        .hasMessageContaining("Topic 'audit-topic' of domain app is declared by")
                        .hasMessageContaining("registry 'main-registry'")
                        .hasMessageContaining("registry 'secondary-registry'"));
    }

    @Test
    void shouldReportTheTopicWhenItsConfigurationIsInvalid() {
        runner.withPropertyValues(
                        APP_REGISTRIES + "[0].name=main-registry",
                        APP_REGISTRIES + "[0].properties.apicurio\\.registry\\.url=" + MAIN_URL,
                        APP_REGISTRIES + "[0].topics[0].name=events-topic")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(InvalidConfigurationException.class)
                        .hasMessageContaining("No schema version could be resolved for topic 'events-topic'")
                        .hasMessageContaining(APP_REGISTRIES + "[0].topics[0].properties"));
    }

    @Test
    void shouldNotValidateATopicThatDisablesTheValidation() {
        withMainRegistry().withPropertyValues(
                        APP_REGISTRIES + "[0].topics[0].properties."
                                + "apicurio\\.registry\\.serde\\.validation-enabled=false")
                .run(context -> {
                    TopicSchemaValidatorRouter router =
                            router(context.getBean(DomainSchemaValidatorProvider.class), "app");
                    assertThat(router.forTopic("events-topic")).isSameAs(NoOpSchemaValidator.INSTANCE);
                    assertThat(router.forTopic("audit-topic")).isInstanceOf(ApicurioSchemaValidator.class);
                });
    }

    @Test
    void shouldFailWhenNoDomainDeclaresARegistry() {
        // The starter exists to validate against a registry, so having it without a single registry declared
        // describes an intention that is not carried out
        runner.run(context -> assertThat(context).hasFailed()
                .getFailure()
                .rootCause()
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("async-commons-kafka-apicurio-starter dependency is present")
                .hasMessageContaining("reactive.commons.kafka.<domain>.apicurio.registries")
                .hasMessageContaining("Declared domains: [app]"));
    }

    @Test
    void shouldReleaseTheRegistryClientsWhenTheContextIsClosed() {
        withMainRegistry().run(context -> {
            assertThat(context.getBean(DomainSchemaValidatorProvider.class)).isInstanceOf(AutoCloseable.class);

            assertThatCode(((ConfigurableApplicationContext) context.getSourceApplicationContext())::close)
                    .doesNotThrowAnyException();
        });
    }

    private TopicSchemaValidatorRouter router(DomainSchemaValidatorProvider provider, String domain) {
        assertThat(provider.forDomain(domain)).isInstanceOf(TopicSchemaValidatorRouter.class);
        return (TopicSchemaValidatorRouter) provider.forDomain(domain);
    }
}

