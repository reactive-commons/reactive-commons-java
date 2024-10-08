package org.reactivecommons.async.starter.impl.rabbit;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.rabbit.RabbitMQBrokerProviderFactory;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.ReactiveCommonsConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {
        RabbitMQConfig.class,
        AsyncPropsDomain.class,
        RabbitMQBrokerProviderFactory.class,
        ReactiveCommonsConfig.class})
class RabbitMQConfigTest {
    @Autowired
    private RabbitJacksonMessageConverter converter;
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
        assertThat(manager.getProviders().get("app").getProps().getAppName()).isEqualTo("test-app");
    }
}
