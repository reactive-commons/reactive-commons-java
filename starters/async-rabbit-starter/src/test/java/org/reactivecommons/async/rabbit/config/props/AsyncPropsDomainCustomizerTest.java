package org.reactivecommons.async.rabbit.config.props;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

class AsyncPropsDomainCustomizerTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<AsyncPropsDomain.RabbitPropsCustomizer> customizerProviderOf(
            AsyncPropsDomain.RabbitPropsCustomizer customizer) {
        ObjectProvider<AsyncPropsDomain.RabbitPropsCustomizer> customizerProvider = mock(ObjectProvider.class);
        when(customizerProvider.getIfAvailable()).thenReturn(customizer);
        return customizerProvider;
    }

    @Test
    void shouldMergeCustomizerWithYamlProperties() {
        // Arrange - simulate YAML-loaded properties
        AsyncRabbitPropsDomainProperties yamlProps = new AsyncRabbitPropsDomainProperties();
        AsyncProps appProps = AsyncProps.builder()
                .withDLQRetry(false)
                .maxRetries(3)
                .retryDelay(10000)
                .listenReplies(false)
                .build();
        yamlProps.put(DEFAULT_DOMAIN, appProps);

        RabbitProperties defaultRabbitProperties = new RabbitProperties();
        defaultRabbitProperties.setHost("localhost");
        defaultRabbitProperties.setPort(5672);

        // Arrange - customizer that overrides specific properties
        RabbitProperties customConnProps = new RabbitProperties();
        customConnProps.setHost("custom-host");
        customConnProps.setPort(5673);

        AsyncPropsDomain.RabbitPropsCustomizer customizer = domainProperties -> {
            AsyncProps props = domainProperties.get(DEFAULT_DOMAIN);
            if (props != null) {
                props.setConnectionProperties(customConnProps);
                props.setRetryDelay(500000);
            }
        };

        AsyncPropsDomain.RabbitSecretFiller secretFiller = (domain, props) -> {
        };

        // Act
        AsyncPropsDomain propsDomain = new AsyncPropsDomain(
                "test-app", defaultRabbitProperties, yamlProps, secretFiller, customizerProviderOf(customizer));

        // Assert - customizer values take precedence
        AsyncProps result = propsDomain.getProps(DEFAULT_DOMAIN);
        assertThat(result.getRetryDelay()).isEqualTo(500000);
        assertThat(result.getConnectionProperties().getHost()).isEqualTo("custom-host");
        assertThat(result.getConnectionProperties().getPort()).isEqualTo(5673);

        // Assert - YAML values are preserved for non-customized properties
        assertThat(result.getWithDLQRetry()).isFalse();
        assertThat(result.getMaxRetries()).isEqualTo(3);
        assertThat(result.getListenReplies()).isFalse();
    }

    @Test
    void shouldWorkWithoutCustomizer() {
        // Arrange - simulate YAML-loaded properties
        AsyncRabbitPropsDomainProperties yamlProps = new AsyncRabbitPropsDomainProperties();
        AsyncProps appProps = AsyncProps.builder()
                .maxRetries(5)
                .retryDelay(2000)
                .listenReplies(true)
                .build();
        yamlProps.put(DEFAULT_DOMAIN, appProps);

        RabbitProperties defaultRabbitProperties = new RabbitProperties();

        AsyncPropsDomain.RabbitSecretFiller secretFiller = (domain, props) -> {
        };

        // Act
        AsyncPropsDomain propsDomain = new AsyncPropsDomain(
                "test-app", defaultRabbitProperties, yamlProps, secretFiller, customizerProviderOf(null));

        // Assert - YAML values are preserved
        AsyncProps result = propsDomain.getProps(DEFAULT_DOMAIN);
        assertThat(result.getMaxRetries()).isEqualTo(5);
        assertThat(result.getRetryDelay()).isEqualTo(2000);
        assertThat(result.getListenReplies()).isTrue();
    }

    @Test
    void shouldAllowCustomizerToAddNewDomains() {
        // Arrange - YAML has only default domain
        AsyncRabbitPropsDomainProperties yamlProps = new AsyncRabbitPropsDomainProperties();
        AsyncProps appProps = AsyncProps.builder()
                .maxRetries(3)
                .listenReplies(true)
                .build();
        yamlProps.put(DEFAULT_DOMAIN, appProps);

        RabbitProperties defaultRabbitProperties = new RabbitProperties();
        defaultRabbitProperties.setHost("localhost");

        RabbitProperties accountsConnProps = new RabbitProperties();
        accountsConnProps.setHost("accounts-host");
        accountsConnProps.setPort(5673);

        // Customizer adds a new domain
        AsyncPropsDomain.RabbitPropsCustomizer customizer = domainProperties -> {
            AsyncProps accountsProps = AsyncProps.builder()
                    .connectionProperties(accountsConnProps)
                    .maxRetries(10)
                    .listenReplies(false)
                    .build();
            domainProperties.put("accounts", accountsProps);
        };

        AsyncPropsDomain.RabbitSecretFiller secretFiller = (domain, props) -> {
        };

        // Act
        AsyncPropsDomain propsDomain = new AsyncPropsDomain(
                "test-app", defaultRabbitProperties, yamlProps, secretFiller, customizerProviderOf(customizer));

        // Assert - original domain is preserved
        AsyncProps appResult = propsDomain.getProps(DEFAULT_DOMAIN);
        assertThat(appResult.getMaxRetries()).isEqualTo(3);

        // Assert - new domain was added by customizer
        AsyncProps accountsResult = propsDomain.getProps("accounts");
        assertThat(accountsResult.getConnectionProperties().getHost()).isEqualTo("accounts-host");
        assertThat(accountsResult.getMaxRetries()).isEqualTo(10);
    }

    @Test
    void shouldCustomizeMultipleYamlDefinedDomains() {
        // Arrange - simulate YAML with two custom domains
        AsyncRabbitPropsDomainProperties yamlProps = new AsyncRabbitPropsDomainProperties();
        AsyncProps pushProps = AsyncProps.builder()
                .withDLQRetry(true)
                .maxRetries(2)
                .listenReplies(false)
                .build();
        yamlProps.put("push", pushProps);

        AsyncProps accountsProps = AsyncProps.builder()
                .listenReplies(false)
                .build();
        RabbitProperties accountsConnProps = new RabbitProperties();
        accountsConnProps.setHost("localhost");
        accountsConnProps.setPort(5673);
        accountsConnProps.setUsername("guest");
        accountsConnProps.setPassword("guest");
        accountsProps.setConnectionProperties(accountsConnProps);
        yamlProps.put("accounts", accountsProps);

        RabbitProperties defaultRabbitProperties = new RabbitProperties();
        defaultRabbitProperties.setHost("localhost");
        defaultRabbitProperties.setPort(5672);

        // Arrange - customizer overrides connection properties on both domains
        RabbitProperties customConnProps = new RabbitProperties();
        customConnProps.setHost("custom-rabbit-host");
        customConnProps.setPort(5671);
        customConnProps.setUsername("admin");
        customConnProps.setPassword("secret");

        AsyncPropsDomain.RabbitPropsCustomizer customizer = domainProperties -> {
            AsyncProps push = domainProperties.get("push");
            if (push != null) {
                push.setConnectionProperties(customConnProps);
                push.setListenReplies(true);
                push.setMaxRetries(3);
            }
            AsyncProps accounts = domainProperties.get("accounts");
            if (accounts != null) {
                accounts.setConnectionProperties(customConnProps);
                accounts.setListenReplies(true);
                accounts.setMaxRetries(3);
            }
        };

        AsyncPropsDomain.RabbitSecretFiller secretFiller = (domain, props) -> {
        };

        // Act
        AsyncPropsDomain propsDomain = new AsyncPropsDomain(
                "ms_sender", defaultRabbitProperties, yamlProps, secretFiller, customizerProviderOf(customizer));

        // Assert - push domain: customizer values take precedence
        AsyncProps pushResult = propsDomain.getProps("push");
        assertThat(pushResult.getConnectionProperties().getHost()).isEqualTo("custom-rabbit-host");
        assertThat(pushResult.getConnectionProperties().getPort()).isEqualTo(5671);
        assertThat(pushResult.getListenReplies()).isTrue();
        assertThat(pushResult.getMaxRetries()).isEqualTo(3);
        // YAML values preserved for non-customized properties
        assertThat(pushResult.getWithDLQRetry()).isTrue();

        // Assert - accounts domain: customizer values take precedence
        AsyncProps accountsResult = propsDomain.getProps("accounts");
        assertThat(accountsResult.getConnectionProperties().getHost()).isEqualTo("custom-rabbit-host");
        assertThat(accountsResult.getConnectionProperties().getPort()).isEqualTo(5671);
        assertThat(accountsResult.getListenReplies()).isTrue();
        assertThat(accountsResult.getMaxRetries()).isEqualTo(3);

        // Assert - defaultDomainName is set to one of the configured domains
        assertThat(propsDomain.getDefaultDomainName()).isIn("push", "accounts");

        // Assert - both domains have the app name set
        assertThat(pushResult.getAppName()).isEqualTo("ms_sender");
        assertThat(accountsResult.getAppName()).isEqualTo("ms_sender");
    }

    @Test
    void shouldWorkWithFourParamConstructor() {
        // Arrange - backward compatibility
        AsyncRabbitPropsDomainProperties yamlProps = new AsyncRabbitPropsDomainProperties();
        AsyncProps appProps = AsyncProps.builder()
                .maxRetries(7)
                .listenReplies(true)
                .build();
        yamlProps.put(DEFAULT_DOMAIN, appProps);

        RabbitProperties defaultRabbitProperties = new RabbitProperties();

        AsyncPropsDomain.RabbitSecretFiller secretFiller = (domain, props) -> {
        };

        // Act - uses 4-param constructor (no customizer)
        AsyncPropsDomain propsDomain = new AsyncPropsDomain(
                "test-app", defaultRabbitProperties, yamlProps, secretFiller);

        // Assert
        AsyncProps result = propsDomain.getProps(DEFAULT_DOMAIN);
        assertThat(result.getMaxRetries()).isEqualTo(7);
    }
}
