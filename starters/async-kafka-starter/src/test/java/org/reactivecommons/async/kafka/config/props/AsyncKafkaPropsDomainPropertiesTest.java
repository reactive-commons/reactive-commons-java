package org.reactivecommons.async.kafka.config.props;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

class AsyncKafkaPropsDomainPropertiesTest {

    private AsyncKafkaPropsDomainProperties withYamlDomain() {
        var properties = new AsyncKafkaPropsDomainProperties();
        var fromYaml = new AsyncKafkaProps();
        fromYaml.setRetryDelay(1000);
        fromYaml.getConnectionProperties().getConsumer().setGroupId("dummy.consumer-group");
        properties.put(DEFAULT_DOMAIN, fromYaml);
        return properties;
    }

    @Test
    void shouldMergeCustomizationsKeepingConfiguredValues() {
        AsyncKafkaPropsDomainProperties properties = withYamlDomain();

        AsyncKafkaProps result = properties.customize(DEFAULT_DOMAIN, app -> {
            app.setMaxRetries(2);
            app.setRetryDelay(30000);
        });

        assertThat(result).isSameAs(properties.get(DEFAULT_DOMAIN));
        assertThat(result.getMaxRetries()).isEqualTo(2);
        assertThat(result.getRetryDelay()).isEqualTo(30000);
        assertThat(result.getConnectionProperties().getConsumer().getGroupId())
                .isEqualTo("dummy.consumer-group");
    }

    @Test
    void shouldCreateDomainWithDefaultsWhenItDoesNotExist() {
        var properties = new AsyncKafkaPropsDomainProperties();

        AsyncKafkaProps result = properties.customize(DEFAULT_DOMAIN, app -> app.setMaxRetries(5));

        assertThat(properties).containsKey(DEFAULT_DOMAIN);
        assertThat(result.getMaxRetries()).isEqualTo(5);
        assertThat(result.getBrokerType()).isEqualTo("kafka");
        assertThat(result.getCreateTopology()).isTrue();
    }

    @Test
    void shouldDiscardConfiguredValuesWhenDomainIsReplacedWithPut() {
        AsyncKafkaPropsDomainProperties properties = withYamlDomain();

        properties.put(DEFAULT_DOMAIN, AsyncKafkaProps.builder().maxRetries(2).build());

        assertThat(properties.get(DEFAULT_DOMAIN).getConnectionProperties().getConsumer().getGroupId()).isNull();
    }
}
