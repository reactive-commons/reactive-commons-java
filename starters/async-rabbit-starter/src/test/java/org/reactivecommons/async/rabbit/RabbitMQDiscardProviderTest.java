package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.DLQDiscardNotifier;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.BrokerConfigProps;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RabbitMQDiscardProviderTest {
    @Mock
    private RabbitJacksonMessageConverter converter;

    @Test
    void shouldCreateDiscardNotifier() {
        // Arrange
        AsyncProps props = new AsyncProps();
        props.setConnectionProperties(new RabbitProperties());
        IBrokerConfigProps brokerConfigProps = new BrokerConfigProps(props);
        props.setBrokerConfigProps(brokerConfigProps);
        BrokerConfig brokerConfig = new BrokerConfig();
        RabbitMQDiscardProvider discardProvider = new RabbitMQDiscardProvider(props, brokerConfig, converter);
        // Act
        DiscardNotifier notifier = discardProvider.get();
        // Assert
        assertThat(notifier).isExactlyInstanceOf(DLQDiscardNotifier.class);
    }
}
