package org.reactivecommons.async.kafka.config.props;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

class AsyncKafkaPropsDomainCustomizerTest {

    @Test
    void shouldMergeCustomizerWithYamlProperties() {
        // Arrange - simulate YAML-loaded properties
        AsyncKafkaPropsDomainProperties yamlProps = new AsyncKafkaPropsDomainProperties();
        AsyncKafkaProps appProps = AsyncKafkaProps.builder()
                .withDLQRetry(false)
                .maxRetries(3)
                .retryDelay(10000)
                .checkExistingTopics(true)
                .build();
        yamlProps.put(DEFAULT_DOMAIN, appProps);

        KafkaProperties defaultKafkaProperties = new KafkaProperties();
        defaultKafkaProperties.setBootstrapServers(List.of("localhost:9092"));

        // Arrange - customizer that overrides specific properties
        KafkaProperties customConnProps = new KafkaProperties();
        customConnProps.setBootstrapServers(List.of("custom-host:9093"));

        AsyncKafkaPropsDomain.KafkaPropsCustomizer customizer = domainProperties -> {
            AsyncKafkaProps props = domainProperties.get(DEFAULT_DOMAIN);
            if (props != null) {
                props.setConnectionProperties(customConnProps);
                props.setRetryDelay(500000);
            }
        };

        @SuppressWarnings("unchecked")
        ObjectProvider<AsyncKafkaPropsDomain.KafkaPropsCustomizer> customizerProvider = mock(ObjectProvider.class);
        when(customizerProvider.getIfAvailable()).thenReturn(customizer);

        AsyncKafkaPropsDomain.KafkaSecretFiller secretFiller = (domain, props) -> {
        };

        // Act
        AsyncKafkaPropsDomain propsDomain = new AsyncKafkaPropsDomain(
                "test-app", defaultKafkaProperties, yamlProps, secretFiller, customizerProvider);

        // Assert - customizer values take precedence
        AsyncKafkaProps result = propsDomain.getProps(DEFAULT_DOMAIN);
        assertThat(result.getRetryDelay()).isEqualTo(500000);
        assertThat(result.getConnectionProperties().getBootstrapServers()).containsExactly("custom-host:9093");

        // Assert - YAML values are preserved for non-customized properties
        assertThat(result.getWithDLQRetry()).isFalse();
        assertThat(result.getMaxRetries()).isEqualTo(3);
        assertThat(result.getCheckExistingTopics()).isTrue();
    }

    @Test
    void shouldWorkWithoutCustomizer() {
        // Arrange
        AsyncKafkaPropsDomainProperties yamlProps = new AsyncKafkaPropsDomainProperties();
        AsyncKafkaProps appProps = AsyncKafkaProps.builder()
                .maxRetries(5)
                .retryDelay(2000)
                .build();
        yamlProps.put(DEFAULT_DOMAIN, appProps);

        KafkaProperties defaultKafkaProperties = new KafkaProperties();

        @SuppressWarnings("unchecked")
        ObjectProvider<AsyncKafkaPropsDomain.KafkaPropsCustomizer> customizerProvider = mock(ObjectProvider.class);
        when(customizerProvider.getIfAvailable()).thenReturn(null);

        AsyncKafkaPropsDomain.KafkaSecretFiller secretFiller = (domain, props) -> {
        };

        // Act
        AsyncKafkaPropsDomain propsDomain = new AsyncKafkaPropsDomain(
                "test-app", defaultKafkaProperties, yamlProps, secretFiller, customizerProvider);

        // Assert - YAML values are preserved
        AsyncKafkaProps result = propsDomain.getProps(DEFAULT_DOMAIN);
        assertThat(result.getMaxRetries()).isEqualTo(5);
        assertThat(result.getRetryDelay()).isEqualTo(2000);
    }

    @Test
    void shouldAllowCustomizerToAddNewDomains() {
        // Arrange - YAML has only default domain
        AsyncKafkaPropsDomainProperties yamlProps = new AsyncKafkaPropsDomainProperties();
        AsyncKafkaProps appProps = AsyncKafkaProps.builder()
                .maxRetries(3)
                .build();
        yamlProps.put(DEFAULT_DOMAIN, appProps);

        KafkaProperties defaultKafkaProperties = new KafkaProperties();
        defaultKafkaProperties.setBootstrapServers(List.of("localhost:9092"));

        KafkaProperties accountsConnProps = new KafkaProperties();
        accountsConnProps.setBootstrapServers(List.of("accounts-host:9093"));

        // Customizer adds a new domain
        AsyncKafkaPropsDomain.KafkaPropsCustomizer customizer = domainProperties -> {
            AsyncKafkaProps accountsProps = AsyncKafkaProps.builder()
                    .connectionProperties(accountsConnProps)
                    .maxRetries(10)
                    .build();
            domainProperties.put("accounts", accountsProps);
        };

        @SuppressWarnings("unchecked")
        ObjectProvider<AsyncKafkaPropsDomain.KafkaPropsCustomizer> customizerProvider = mock(ObjectProvider.class);
        when(customizerProvider.getIfAvailable()).thenReturn(customizer);

        AsyncKafkaPropsDomain.KafkaSecretFiller secretFiller = (domain, props) -> {
        };

        // Act
        AsyncKafkaPropsDomain propsDomain = new AsyncKafkaPropsDomain(
                "test-app", defaultKafkaProperties, yamlProps, secretFiller, customizerProvider);

        // Assert - original domain is preserved
        AsyncKafkaProps appResult = propsDomain.getProps(DEFAULT_DOMAIN);
        assertThat(appResult.getMaxRetries()).isEqualTo(3);

        // Assert - new domain was added by customizer
        AsyncKafkaProps accountsResult = propsDomain.getProps("accounts");
        assertThat(accountsResult.getConnectionProperties().getBootstrapServers()).containsExactly("accounts-host:9093");
        assertThat(accountsResult.getMaxRetries()).isEqualTo(10);
    }

    @Test
    void shouldWorkWithFourParamConstructor() {
        // Arrange - backward compatibility
        AsyncKafkaPropsDomainProperties yamlProps = new AsyncKafkaPropsDomainProperties();
        AsyncKafkaProps appProps = AsyncKafkaProps.builder()
                .maxRetries(7)
                .build();
        yamlProps.put(DEFAULT_DOMAIN, appProps);

        KafkaProperties defaultKafkaProperties = new KafkaProperties();

        AsyncKafkaPropsDomain.KafkaSecretFiller secretFiller = (domain, props) -> {
        };

        // Act - uses 4-param constructor (no customizer)
        AsyncKafkaPropsDomain propsDomain = new AsyncKafkaPropsDomain(
                "test-app", defaultKafkaProperties, yamlProps, secretFiller);

        // Assert
        AsyncKafkaProps result = propsDomain.getProps(DEFAULT_DOMAIN);
        assertThat(result.getMaxRetries()).isEqualTo(7);
    }
}
