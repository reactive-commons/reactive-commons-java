package org.reactivecommons.async.starter.impl.common.kafka;


import org.junit.jupiter.api.Test;
import org.reactivecommons.async.kafka.KafkaBrokerProviderFactory;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.ReactiveCommonsConfig;
import org.reactivecommons.async.starter.config.ReactiveCommonsListenersConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {
        RCKafkaConfig.class,
        AsyncKafkaPropsDomain.class,
        KafkaBrokerProviderFactory.class,
        ReactiveCommonsConfig.class,
        ReactiveCommonsListenersConfig.class
})
class KafkaConfigTest {
    @Autowired
    private KafkaJacksonMessageConverter converter;
    @Autowired
    private ConnectionManager manager;

    @Test
    void shouldHasConverter() {
        // Arrange
        // Act
        // Assert
        assertThat(converter).isNotNull();
    }

    @Test
    void shouldHasManager() {
        // Arrange
        // Act
        // Assert
        assertThat(manager).isNotNull();
        assertThat(manager.getProviders()).isNotEmpty();
        assertThat(manager.getProviders().get("app").getProps().getAppName()).isEqualTo("async-kafka-starter");
    }
}
