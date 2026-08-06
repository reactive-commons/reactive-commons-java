package org.reactivecommons.async.kafka.config.props;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.kafka.KafkaBrokerProviderFactory;
import org.reactivecommons.async.starter.config.ReactiveCommonsConfig;
import org.reactivecommons.async.starter.impl.common.kafka.RCKafkaConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {
        RCKafkaConfig.class,
        AsyncKafkaPropsDomain.class,
        KafkaBrokerProviderFactory.class,
        ReactiveCommonsConfig.class
}, properties = {
        "reactive.commons.kafka.app.connectionProperties.consumer.group-id=dummy.consumer-group",
        "reactive.commons.kafka.accounts.checkExistingTopics=false",
        "reactive.commons.kafka.accounts.connectionProperties.bootstrap-servers=localhost:9093",
        "reactive.commons.kafka.accounts.domain.events.events-suffix=subsEvents",
        "reactive.commons.kafka.notifications.checkExistingTopics=false",
        "reactive.commons.kafka.notifications.connectionProperties.properties.group.id=raw.consumer-group"
})
class AsyncKafkaPropsEventsGroupIdTest {

    @Autowired
    private AsyncKafkaPropsDomain propsDomain;

    @Test
    void shouldBindGroupIdFromConnectionPropertiesConsumer() {
        AsyncKafkaProps app = propsDomain.getProps("app");

        assertThat(app.getConnectionProperties().getConsumer().getGroupId())
                .isEqualTo("dummy.consumer-group");
        assertThat(app.resolveEventsGroupId()).isEqualTo("dummy.consumer-group");
    }

    @Test
    void shouldBindGroupIdFromRawConnectionProperties() {
        AsyncKafkaProps notifications = propsDomain.getProps("notifications");

        assertThat(notifications.resolveEventsGroupId()).isEqualTo("raw.consumer-group");
    }

    @Test
    void shouldBindEventsSuffixPerDomainWhenNoGroupIdIsConfigured() {
        AsyncKafkaProps accounts = propsDomain.getProps("accounts");

        assertThat(accounts.getConnectionProperties().getConsumer().getGroupId()).isNull();
        assertThat(accounts.resolveEventsGroupId()).isEqualTo("async-kafka-starter-subsEvents");
    }
}
