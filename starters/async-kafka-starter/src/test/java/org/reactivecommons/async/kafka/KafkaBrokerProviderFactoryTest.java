package org.reactivecommons.async.kafka;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.KafkaReceiverOptionsCustomizer;
import org.reactivecommons.async.kafka.config.KafkaSenderOptionsCustomizer;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import org.reactivecommons.async.kafka.metrics.KafkaMetricsFactory;
import org.reactivecommons.async.kafka.validation.DomainSchemaValidatorProvider;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.broker.BrokerProviderFactory;
import org.reactivecommons.async.starter.broker.DiscardProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.ssl.SslBundles;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaBrokerProviderFactoryTest {
    @Mock
    private KafkaJacksonMessageConverter converter;
    @Mock
    private CustomReporter errorReporter;
    @Mock
    private KafkaCustomizations customizations;
    @Mock
    private ObjectProvider<SchemaValidator> schemaValidatorProvider;
    @Mock
    private ObjectProvider<DomainSchemaValidatorProvider> domainSchemaValidatorProvider;

    private BrokerProviderFactory<AsyncKafkaProps> providerFactory;
    /**
     * Closed after each test: an open provider would keep its admin client trying to reach the cluster.
     */
    private final List<BrokerProvider<AsyncKafkaProps>> providers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        providerFactory = new KafkaBrokerProviderFactory(converter, errorReporter, customizations,
                new KafkaSchemaValidators(schemaValidatorProvider, domainSchemaValidatorProvider),
                new KafkaClientSettingsFactory(none(SslBundles.class), none(KafkaReceiverOptionsCustomizer.class),
                        none(KafkaSenderOptionsCustomizer.class), none(KafkaMetricsFactory.class)));
    }

    private static <T> ObjectProvider<T> none(Class<T> type) {
        return new StaticListableBeanFactory().getBeanProvider(type);
    }

    @AfterEach
    void closeProviders() {
        providers.forEach(BrokerProvider::close);
    }

    private BrokerProvider<AsyncKafkaProps> provider(String domain, AsyncKafkaProps props,
                                                     DiscardProvider discardProvider) {
        BrokerProvider<AsyncKafkaProps> provider = providerFactory.getProvider(domain, props, discardProvider);
        providers.add(provider);
        return provider;
    }

    /**
     * KC-12: creating the providers does not connect to the cluster, which is checked by default
     * ({@code checkExistingTopics: true}), so the application starts even when the broker cannot be reached.
     */
    @Test
    void shouldCreateTheProviderWithoutReachingTheCluster() {
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.getConnectionProperties().setBootstrapServers(List.of("localhost:1"));
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> provider("app", props, discardProvider));
    }

    /**
     * KC-31: the default domain shares the admin client of the discard notifier it publishes the discards of the
     * other domains with, instead of opening one more.
     */
    @Test
    void shouldShareTheTopologyCreatorOfTheDiscardNotifierWithTheDefaultDomain() {
        AsyncKafkaProps props = new AsyncKafkaProps();
        KafkaDiscardProvider discardProvider = (KafkaDiscardProvider) providerFactory.getDiscardProvider(props);

        KafkaBrokerProvider app = (KafkaBrokerProvider) provider("app", props, discardProvider);
        KafkaBrokerProvider accounts = (KafkaBrokerProvider) provider("accounts", new AsyncKafkaProps(),
                discardProvider);

        TopologyCreator shared = discardProvider.shareTopologyCreator();
        try {
            assertThat(app.components().topologyCreator()).isSameAs(shared);
            assertThat(accounts.components().topologyCreator()).isNotSameAs(shared);
        } finally {
            shared.close();
        }
    }

    @Test
    void shouldReturnBrokerType() {
        // Arrange
        // Act
        String brokerType = providerFactory.getBrokerType();
        // Assert
        assertEquals("kafka", brokerType);
    }

    @Test
    void shouldReturnCreateDiscardProvider() {
        // Arrange
        AsyncKafkaProps props = new AsyncKafkaProps();
        // Act
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Assert
        assertThat(discardProvider).isInstanceOf(KafkaDiscardProvider.class);
    }

    @Test
    void shouldReturnBrokerProvider() {
        // Arrange
        AsyncKafkaProps props = new AsyncKafkaProps();
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Act
        BrokerProvider<AsyncKafkaProps> brokerProvider = provider("domain", props, discardProvider);
        // Assert
        assertThat(brokerProvider).isInstanceOf(KafkaBrokerProvider.class);
    }

    @Test
    void shouldAskTheSchemaValidatorOfEachDomain() {
        // Arrange
        AsyncKafkaProps props = new AsyncKafkaProps();
        DomainSchemaValidatorProvider byDomain = mock(DomainSchemaValidatorProvider.class);
        SchemaValidator validator = mock(SchemaValidator.class);
        when(byDomain.forDomain(any())).thenReturn(validator);
        when(domainSchemaValidatorProvider.getIfAvailable()).thenReturn(byDomain);
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Act
        provider("app", props, discardProvider);
        provider("accounts", props, discardProvider);
        // Assert
        verify(byDomain).forDomain("app");
        verify(byDomain).forDomain("accounts");
    }

    @Test
    void shouldWireTheValidatorOfEachDomainIntoItsOwnListener() {
        // Arrange
        AsyncKafkaProps props = new AsyncKafkaProps();
        SchemaValidator appValidator = mock(SchemaValidator.class);
        SchemaValidator accountsValidator = mock(SchemaValidator.class);
        DomainSchemaValidatorProvider byDomain = mock(DomainSchemaValidatorProvider.class);
        when(byDomain.forDomain("app")).thenReturn(appValidator);
        when(byDomain.forDomain("accounts")).thenReturn(accountsValidator);
        when(domainSchemaValidatorProvider.getIfAvailable()).thenReturn(byDomain);
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Act
        KafkaBrokerProvider app = (KafkaBrokerProvider) provider("app", props, discardProvider);
        KafkaBrokerProvider accounts =
                (KafkaBrokerProvider) provider("accounts", props, discardProvider);
        // Assert
        assertThat(app.components().receiver().getSchemaValidator()).isSameAs(appValidator);
        assertThat(accounts.components().receiver().getSchemaValidator()).isSameAs(accountsValidator);
    }

    @Test
    void shouldNotValidateADomainWithoutValidator() {
        // Arrange
        AsyncKafkaProps props = new AsyncKafkaProps();
        DomainSchemaValidatorProvider byDomain = mock(DomainSchemaValidatorProvider.class);
        when(byDomain.forDomain("legacy")).thenReturn(null);
        when(domainSchemaValidatorProvider.getIfAvailable()).thenReturn(byDomain);
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Act
        KafkaBrokerProvider legacy =
                (KafkaBrokerProvider) provider("legacy", props, discardProvider);
        // Assert
        assertThat(legacy.components().receiver().getSchemaValidator()).isInstanceOf(NoOpSchemaValidator.class);
    }

    @Test
    void shouldPreferAGlobalSchemaValidatorBeanOverThePerDomainOne() {
        // Arrange
        AsyncKafkaProps props = new AsyncKafkaProps();
        SchemaValidator global = mock(SchemaValidator.class);
        when(schemaValidatorProvider.getIfAvailable()).thenReturn(global);
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Act
        provider("accounts", props, discardProvider);
        // Assert
        verify(domainSchemaValidatorProvider, never()).getIfAvailable();
    }
}
