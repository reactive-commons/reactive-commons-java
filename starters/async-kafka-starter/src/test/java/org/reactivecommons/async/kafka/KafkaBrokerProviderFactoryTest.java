package org.reactivecommons.async.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import org.reactivecommons.async.kafka.validation.DomainSchemaValidatorProvider;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.broker.BrokerProviderFactory;
import org.reactivecommons.async.starter.broker.DiscardProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaBrokerProviderFactoryTest {
    private final ReactiveReplyRouter router = new ReactiveReplyRouter();
    @Mock
    private KafkaJacksonMessageConverter converter;
    @Mock
    private MeterRegistry meterRegistry;
    @Mock
    private CustomReporter errorReporter;
    @Mock
    private KafkaCustomizations customizations;
    @Mock
    private SslBundles sslBundles;
    @Mock
    private ObjectProvider<SchemaValidator> schemaValidatorProvider;
    @Mock
    private ObjectProvider<DomainSchemaValidatorProvider> domainSchemaValidatorProvider;

    private BrokerProviderFactory<AsyncKafkaProps> providerFactory;

    @BeforeEach
    void setUp() {
        providerFactory = new KafkaBrokerProviderFactory(router, converter, meterRegistry, errorReporter,
                customizations, sslBundles, schemaValidatorProvider, domainSchemaValidatorProvider);
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
        props.setCheckExistingTopics(false);
        // Act
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Assert
        assertThat(discardProvider).isInstanceOf(KafkaDiscardProvider.class);
    }

    @Test
    void shouldReturnBrokerProvider() {
        // Arrange
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.setCheckExistingTopics(false);
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Act
        BrokerProvider<AsyncKafkaProps> brokerProvider = providerFactory.getProvider("domain", props, discardProvider);
        // Assert
        assertThat(brokerProvider).isInstanceOf(KafkaBrokerProvider.class);
    }

    @Test
    void shouldAskTheSchemaValidatorOfEachDomain() {
        // Arrange
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.setCheckExistingTopics(false);
        DomainSchemaValidatorProvider byDomain = mock(DomainSchemaValidatorProvider.class);
        SchemaValidator validator = mock(SchemaValidator.class);
        when(byDomain.forDomain(any())).thenReturn(validator);
        when(domainSchemaValidatorProvider.getIfAvailable()).thenReturn(byDomain);
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Act
        providerFactory.getProvider("app", props, discardProvider);
        providerFactory.getProvider("accounts", props, discardProvider);
        // Assert
        verify(byDomain).forDomain("app");
        verify(byDomain).forDomain("accounts");
    }

    @Test
    void shouldWireTheValidatorOfEachDomainIntoItsOwnListener() {
        // Arrange
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.setCheckExistingTopics(false);
        SchemaValidator appValidator = mock(SchemaValidator.class);
        SchemaValidator accountsValidator = mock(SchemaValidator.class);
        DomainSchemaValidatorProvider byDomain = mock(DomainSchemaValidatorProvider.class);
        when(byDomain.forDomain("app")).thenReturn(appValidator);
        when(byDomain.forDomain("accounts")).thenReturn(accountsValidator);
        when(domainSchemaValidatorProvider.getIfAvailable()).thenReturn(byDomain);
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Act
        KafkaBrokerProvider app = (KafkaBrokerProvider) providerFactory.getProvider("app", props, discardProvider);
        KafkaBrokerProvider accounts =
                (KafkaBrokerProvider) providerFactory.getProvider("accounts", props, discardProvider);
        // Assert
        assertThat(app.receiver().getSchemaValidator()).isSameAs(appValidator);
        assertThat(accounts.receiver().getSchemaValidator()).isSameAs(accountsValidator);
    }

    @Test
    void shouldNotValidateADomainWithoutValidator() {
        // Arrange
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.setCheckExistingTopics(false);
        DomainSchemaValidatorProvider byDomain = mock(DomainSchemaValidatorProvider.class);
        when(byDomain.forDomain("legacy")).thenReturn(null);
        when(domainSchemaValidatorProvider.getIfAvailable()).thenReturn(byDomain);
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Act
        KafkaBrokerProvider legacy =
                (KafkaBrokerProvider) providerFactory.getProvider("legacy", props, discardProvider);
        // Assert
        assertThat(legacy.receiver().getSchemaValidator()).isInstanceOf(NoOpSchemaValidator.class);
    }

    @Test
    void shouldPreferAGlobalSchemaValidatorBeanOverThePerDomainOne() {
        // Arrange
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.setCheckExistingTopics(false);
        SchemaValidator global = mock(SchemaValidator.class);
        when(schemaValidatorProvider.getIfAvailable()).thenReturn(global);
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Act
        providerFactory.getProvider("accounts", props, discardProvider);
        // Assert
        verify(domainSchemaValidatorProvider, never()).getIfAvailable();
    }
}
