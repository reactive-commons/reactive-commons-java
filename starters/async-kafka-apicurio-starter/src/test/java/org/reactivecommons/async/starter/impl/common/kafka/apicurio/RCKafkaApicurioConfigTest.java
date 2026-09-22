package org.reactivecommons.async.starter.impl.common.kafka.apicurio;

import com.networknt.schema.JsonSchema;
import io.apicurio.registry.resolver.SchemaResolver;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.kafka.apicurio.SharedSchemaResolvers;
import org.reactivecommons.async.kafka.apicurio.TopicSchemaValidatorRouter;
import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomainProperties;
import org.reactivecommons.async.kafka.validation.DomainSchemaValidatorProvider;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Covers the bean the starter registers, which decides the validator of every domain: a router for the domains that
 * declare registries, and the no-op validator for the ones that do not.
 */
@SuppressWarnings("unchecked")
class RCKafkaApicurioConfigTest {

    private static final String MAIN_URL = "http://localhost:8080/apis/registry/v3";

    /**
     * Provides the domain properties the way the Kafka starter does, so the Apicurio configuration is bound from
     * {@code reactive.commons.kafka.<domain>.apicurio}.
     * <p>
     * Deliberately not annotated with {@code @Configuration}: this package is the one scanned by
     * {@code ReactiveCommonsConfig}, so the annotation would make it a candidate for component scan and its
     * {@code AsyncKafkaPropsDomain} would leak into every other test context. It is applied explicitly with
     * {@code withUserConfiguration}, which does not need the annotation.
     */
    @EnableConfigurationProperties(AsyncKafkaPropsDomainProperties.class)
    static class DomainPropertiesConfig {
        @Bean
        public AsyncKafkaPropsDomain asyncKafkaPropsDomain(AsyncKafkaPropsDomainProperties configured) {
            return new AsyncKafkaPropsDomain("test-app", new KafkaProperties(), configured,
                    (ignoredDomain, ignoredProps) -> {
                    });
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DomainPropertiesConfig.class)
            .withConfiguration(AutoConfigurations.of(RCKafkaApicurioConfig.class))
            .withPropertyValues(
                    "reactive.commons.kafka.app.connection-properties.bootstrap-servers=broker-a:9092",
                    "reactive.commons.kafka.app.apicurio.registries[0].name=main-registry",
                    "reactive.commons.kafka.app.apicurio.registries[0].properties."
                            + "apicurio\\.registry\\.url=" + MAIN_URL,
                    // find-latest defaults to false as in Apicurio, so every topic has to decide how the version is
                    // resolved. Opting into the latest one is the shortest way to a usable configuration.
                    "reactive.commons.kafka.app.apicurio.registries[0].properties."
                            + "apicurio\\.registry\\.find-latest=true",
                    "reactive.commons.kafka.app.apicurio.registries[0].topics[0].name=events-topic");

    @Test
    void shouldRegisterApicurioValidatorProvider() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DomainSchemaValidatorProvider.class);
            assertThat(context.getBean(DomainSchemaValidatorProvider.class).forDomain("app"))
                    .isInstanceOf(TopicSchemaValidatorRouter.class);
        });
    }

    @Test
    void shouldNotCreateAPhantomDomainForTheApicurioConfiguration() {
        runner.run(context -> assertThat(context.getBean(AsyncKafkaPropsDomain.class)).containsOnlyKeys("app"));
    }

    @Test
    void shouldNotValidateADomainThatDeclaresNoRegistry() {
        runner.withPropertyValues(
                        "reactive.commons.kafka.accounts.connection-properties.bootstrap-servers=broker-b:9092")
                .run(context -> {
                    DomainSchemaValidatorProvider provider = context.getBean(DomainSchemaValidatorProvider.class);
                    assertThat(provider.forDomain("accounts")).isInstanceOf(NoOpSchemaValidator.class);
                    assertThat(provider.forDomain("app")).isInstanceOf(TopicSchemaValidatorRouter.class);
                });
    }

    @Test
    void shouldNotValidateDomainsThatAreNotConfigured() {
        runner.run(context -> assertThat(context.getBean(DomainSchemaValidatorProvider.class)
                .forDomain("undeclared")).isInstanceOf(NoOpSchemaValidator.class));
    }

    @Test
    void shouldGiveEachDomainItsOwnValidator() {
        runner.withPropertyValues(
                        "reactive.commons.kafka.accounts.connection-properties.bootstrap-servers=broker-b:9092",
                        "reactive.commons.kafka.accounts.apicurio.registries[0].name=accounts-registry",
                        "reactive.commons.kafka.accounts.apicurio.registries[0].properties."
                                + "apicurio\\.registry\\.url=http://accounts/apis/registry/v3",
                        "reactive.commons.kafka.accounts.apicurio.registries[0].properties."
                                + "apicurio\\.registry\\.find-latest=true",
                        "reactive.commons.kafka.accounts.apicurio.registries[0].topics[0].name=audit-topic")
                .run(context -> {
                    DomainSchemaValidatorProvider provider = context.getBean(DomainSchemaValidatorProvider.class);
                    assertThat(provider.forDomain("accounts"))
                            .isInstanceOf(TopicSchemaValidatorRouter.class)
                            .isNotSameAs(provider.forDomain("app"));
                });
    }

    @Test
    void shouldShareOneRegistryClientEvenWhenTheDomainsUseDifferentBrokers() {
        runner.withPropertyValues(
                        "reactive.commons.kafka.accounts.connection-properties.bootstrap-servers=broker-b:9092",
                        "reactive.commons.kafka.accounts.apicurio.registries[0].name=accounts-registry",
                        // The very same registry as the default domain
                        "reactive.commons.kafka.accounts.apicurio.registries[0].properties."
                                + "apicurio\\.registry\\.url=" + MAIN_URL,
                        "reactive.commons.kafka.accounts.apicurio.registries[0].properties."
                                + "apicurio\\.registry\\.find-latest=true",
                        "reactive.commons.kafka.accounts.apicurio.registries[0].properties."
                                + "apicurio\\.registry\\.artifact\\.group-id=accounts",
                        "reactive.commons.kafka.accounts.apicurio.registries[0].topics[0].name=audit-topic")
                .run(context -> {
                    AsyncKafkaPropsDomain domains = context.getBean(AsyncKafkaPropsDomain.class);
                    assertThat(domains.getProps("app").getConnectionProperties().getBootstrapServers())
                            .isNotEqualTo(domains.getProps("accounts").getConnectionProperties()
                                    .getBootstrapServers());

                    SharedSchemaResolvers resolvers = new SharedSchemaResolvers();
                    Map<String, SchemaValidator> validators =
                            RCKafkaApicurioConfig.buildValidators(domains, resolvers);

                    // Neither the broker nor the group is part of the registry configuration, so a single client
                    // serves both domains
                    assertThat(resolvers.count()).isOne();
                    assertThat(validators).containsOnlyKeys("app", "accounts");
                    assertThat(validators.get("app")).isNotSameAs(validators.get("accounts"));
                });
    }

    @Test
    void shouldReleaseTheRegistryClientsWhenTheContextIsClosed() {
        runner.run(context -> {
            DomainSchemaValidatorProvider provider = context.getBean(DomainSchemaValidatorProvider.class);
            // Spring infers close() as the destroy method of any AutoCloseable bean
            assertThat(provider).isInstanceOf(AutoCloseable.class);

            assertThatCode(((ConfigurableApplicationContext) context.getSourceApplicationContext())::close)
                    .doesNotThrowAnyException();
        });
    }

    @Test
    void shouldReleaseTheRegistryClientsItCreated() throws Exception {
        SchemaResolver<JsonSchema, Object> resolver = mock(SchemaResolver.class);
        var resolvers = new SharedSchemaResolvers(config -> resolver);
        resolvers.forRegistry(Map.of("apicurio.registry.url", "http://registry:8080"));

        RCKafkaApicurioConfig.ApicurioValidatorProvider provider =
                new RCKafkaApicurioConfig.ApicurioValidatorProvider(Map.of(), resolvers);
        provider.close();

        verify(resolver).close();
        assertThat(provider.forDomain("undeclared")).isInstanceOf(NoOpSchemaValidator.class);
    }

    @Test
    void shouldLetACustomProviderShareOneValidatorAcrossDomains() {
        SchemaValidator shared = mock(SchemaValidator.class);

        runner.withBean(DomainSchemaValidatorProvider.class, () -> domain -> shared)
                .run(context -> {
                    DomainSchemaValidatorProvider provider = context.getBean(DomainSchemaValidatorProvider.class);
                    // A user provided bean replaces the one of the starter, which is how a single registry
                    // client and schema cache are shared by every domain
                    assertThat(provider.forDomain("app")).isSameAs(shared);
                    assertThat(provider.forDomain("accounts")).isSameAs(shared);
                });
    }
}

