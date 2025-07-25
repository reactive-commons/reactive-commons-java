package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageHandler;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.BrokerConfigProps;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivecommons.async.rabbit.discard.RabbitMQDiscardProviderImpl;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.broker.BrokerProviderFactory;
import org.reactivecommons.async.starter.broker.DiscardProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class RabbitMQBrokerProviderFactoryTest {
    private final BrokerConfig config = new BrokerConfig();
    private final ReactiveReplyRouter router = new ReactiveReplyRouter();
    @Mock
    private RabbitJacksonMessageConverter converter;
    @Mock
    private MeterRegistry meterRegistry;
    @Mock
    private CustomReporter errorReporter;
    @Mock
    private UnroutableMessageHandler unroutableMessageHandler;

    private BrokerProviderFactory<AsyncProps> providerFactory;

    @BeforeEach
    void setUp() {
        providerFactory = new RabbitMQBrokerProviderFactory(config, router, converter, meterRegistry, errorReporter,
                RabbitMQDiscardProviderImpl::new, unroutableMessageHandler);
    }

    @Test
    void shouldReturnBrokerType() {
        // Arrange
        // Act
        String brokerType = providerFactory.getBrokerType();
        // Assert
        assertEquals("rabbitmq", brokerType);
    }

    @Test
    void shouldReturnCreateDiscardProvider() {
        // Arrange
        AsyncProps props = new AsyncProps();
        // Act
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Assert
        assertThat(discardProvider).isInstanceOf(RabbitMQDiscardProviderImpl.class);
    }

    @Test
    void shouldReturnBrokerProvider() {
        // Arrange
        AsyncProps props = new AsyncProps();
        props.setConnectionProperties(new RabbitProperties());
        IBrokerConfigProps brokerConfigProps = new BrokerConfigProps(props);
        props.setBrokerConfigProps(brokerConfigProps);
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Act
        BrokerProvider<AsyncProps> brokerProvider = providerFactory.getProvider("domain", props, discardProvider);
        // Assert
        assertThat(brokerProvider).isInstanceOf(RabbitMQBrokerProvider.class);
    }
}
