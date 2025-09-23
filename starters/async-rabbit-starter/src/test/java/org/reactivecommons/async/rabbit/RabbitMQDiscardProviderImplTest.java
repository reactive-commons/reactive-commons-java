package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
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
import org.reactivecommons.async.rabbit.discard.RabbitMQDiscardProviderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RabbitMQDiscardProviderImplTest {
    @Mock
    private RabbitJacksonMessageConverter converter;

    @Mock
    private ConnectionFactoryCustomizer cfCustomizer;

    @BeforeEach
    void setUp() {
        when(cfCustomizer.customize(any(ConnectionFactory.class), any(AsyncProps.class)))
                .thenAnswer(invocation -> invocation.<ConnectionFactory>getArgument(1));
    }

    @Test
    void shouldCreateDiscardNotifier() {
        // Arrange
        AsyncProps props = new AsyncProps();
        props.setConnectionProperties(new RabbitProperties());
        IBrokerConfigProps brokerConfigProps = new BrokerConfigProps(props);
        props.setBrokerConfigProps(brokerConfigProps);
        BrokerConfig brokerConfig = new BrokerConfig();
        var discardProvider = new RabbitMQDiscardProviderImpl(props, brokerConfig, converter, cfCustomizer);
        // Act
        DiscardNotifier notifier = discardProvider.get();
        // Assert
        assertThat(notifier).isExactlyInstanceOf(DLQDiscardNotifier.class);
    }
}
