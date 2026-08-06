package org.reactivecommons.async.kafka.config.props;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.kafka.config.KafkaProperties;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncKafkaPropsGroupIdTest {

    private AsyncKafkaProps propsWith(KafkaProperties connectionProperties) {
        var props = new AsyncKafkaProps();
        props.setAppName("sample-app");
        props.setConnectionProperties(connectionProperties);
        return props;
    }

    @Test
    void shouldResolveDefaultGroupIdFromAppNameWhenNoGroupIdConfigured() {
        AsyncKafkaProps props = propsWith(new KafkaProperties());

        assertThat(props.resolveEventsGroupId()).isEqualTo("sample-app-events");
    }

    @Test
    void shouldResolveGroupIdFromConsumerProperty() {
        var connectionProperties = new KafkaProperties();
        connectionProperties.getConsumer().setGroupId("dummy.consumer-group");

        assertThat(propsWith(connectionProperties).resolveEventsGroupId())
                .isEqualTo("dummy.consumer-group");
    }

    @Test
    void shouldResolveGroupIdFromCommonRawProperties() {
        var connectionProperties = new KafkaProperties();
        connectionProperties.getProperties().put("group.id", "dummy.consumer-group");

        assertThat(propsWith(connectionProperties).resolveEventsGroupId())
                .isEqualTo("dummy.consumer-group");
    }

    @Test
    void shouldResolveGroupIdFromConsumerRawProperties() {
        var connectionProperties = new KafkaProperties();
        connectionProperties.getConsumer().getProperties().put("group.id", "raw.consumer-group");

        assertThat(propsWith(connectionProperties).resolveEventsGroupId()).isEqualTo("raw.consumer-group");
    }

    @Test
    void shouldPreferConsumerGroupIdOverCommonRawProperties() {
        var connectionProperties = new KafkaProperties();
        connectionProperties.getProperties().put("group.id", "common.consumer-group");
        connectionProperties.getConsumer().setGroupId("consumer.consumer-group");

        assertThat(propsWith(connectionProperties).resolveEventsGroupId()).isEqualTo("consumer.consumer-group");
    }

    @Test
    void shouldIgnoreBlankGroupId() {
        var connectionProperties = new KafkaProperties();
        connectionProperties.getConsumer().setGroupId("   ");

        assertThat(propsWith(connectionProperties).resolveEventsGroupId()).isEqualTo("sample-app-events");
    }

    @Test
    void shouldResolveDefaultGroupIdWhenConnectionPropertiesAreNotSet() {
        var props = new AsyncKafkaProps();
        props.setAppName("sample-app");
        props.setConnectionProperties(null);

        assertThat(props.resolveEventsGroupId()).isEqualTo("sample-app-events");
    }
}
