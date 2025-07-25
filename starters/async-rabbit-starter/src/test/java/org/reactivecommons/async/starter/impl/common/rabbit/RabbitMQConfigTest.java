package org.reactivecommons.async.starter.impl.common.rabbit;

import com.rabbitmq.client.AMQP;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.reactivecommons.async.rabbit.RabbitMQBrokerProviderFactory;
import org.reactivecommons.async.rabbit.RabbitMQFactory;
import org.reactivecommons.async.rabbit.communications.MyOutboundMessage;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageHandler;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.ReactiveCommonsConfig;
import org.reactivecommons.async.starter.config.ReactiveCommonsListenersConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = {
        RabbitMQConfig.class,
        AsyncPropsDomain.class,
        RabbitMQBrokerProviderFactory.class,
        ReactiveCommonsConfig.class,
        ReactiveCommonsListenersConfig.class
})
class RabbitMQConfigTest {
    @Autowired
    private RabbitJacksonMessageConverter converter;

    @Autowired
    private ConnectionManager manager;

    @Mock
    private OutboundMessageResult<MyOutboundMessage> resultMock;

    @Mock
    private MyOutboundMessage outboundMessageMock;

    private RabbitMQConfig rabbitMQConfig;

    @BeforeEach
    void setUp() {
        rabbitMQConfig = new RabbitMQConfig();
    }

    @Test
    void shouldHasConverter() {
        assertThat(converter).isNotNull();
    }

    @Test
    void shouldHasManager() {
        assertThat(manager).isNotNull();
        assertThat(manager.getProviders()).isNotEmpty();
        assertThat(manager.getProviders().get("app").getProps().getAppName()).isEqualTo("test-app");
    }

    @Test
    void shouldProcessAndLogWhenMessageIsReturned() {
        // Arrange
        UnroutableMessageHandler handler = rabbitMQConfig.defaultUnroutableMessageHandler();
        when(resultMock.getOutboundMessage()).thenReturn(outboundMessageMock);
        when(outboundMessageMock.getExchange()).thenReturn("test.exchange");
        when(outboundMessageMock.getRoutingKey()).thenReturn("test.key");
        when(outboundMessageMock.getBody()).thenReturn("test message".getBytes(StandardCharsets.UTF_8));
        when(outboundMessageMock.getProperties()).thenReturn(new AMQP.BasicProperties());

        // Act & Assert
        StepVerifier.create(handler.processMessage(resultMock))
                .verifyComplete();

        verify(resultMock).getOutboundMessage();
        verify(outboundMessageMock).getExchange();
        verify(outboundMessageMock).getRoutingKey();
        verify(outboundMessageMock).getBody();
        verify(outboundMessageMock).getProperties();
    }

    @Test
    void shouldCreateReactiveMQFactory() {
        RabbitMQFactory factory = rabbitMQConfig.reactiveMQFactory(converter);
        assertThat(factory).isNotNull();
    }
}
