package org.reactivecommons.async.starter.impl.common.rabbit;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.rabbit.ConnectionFactoryCustomizer;
import org.reactivecommons.async.rabbit.RabbitMQBrokerProviderFactory;
import org.reactivecommons.async.rabbit.communications.MyOutboundMessage;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageHandler;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageNotifier;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageProcessor;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = {
        RabbitMQConfig.class,
        AsyncPropsDomain.class,
        RabbitMQBrokerProviderFactory.class,
        ReactiveCommonsConfig.class,
        ReactiveCommonsListenersConfig.class
})
@ExtendWith(MockitoExtension.class)
class RabbitMQConfigTest {
    @Autowired
    private RabbitJacksonMessageConverter converter;

    @Autowired
    private ConnectionManager manager;

    @Mock
    private OutboundMessageResult<MyOutboundMessage> resultMock;

    @Mock
    private MyOutboundMessage outboundMessageMock;
    @Mock
    private UnroutableMessageNotifier unroutableMessageNotifier;

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
        assertThat(manager.getProviders().get("app").props().getAppName()).isEqualTo("test-app");
    }

    @Test
    void shouldProcessAndLogWhenMessageIsReturned() {
        UnroutableMessageHandler handler = rabbitMQConfig.defaultUnroutableMessageProcessor(unroutableMessageNotifier);
        when(resultMock.getOutboundMessage()).thenReturn(outboundMessageMock);
        when(outboundMessageMock.getExchange()).thenReturn("test.exchange");
        when(outboundMessageMock.getRoutingKey()).thenReturn("test.key");
        when(outboundMessageMock.getBody()).thenReturn("test message".getBytes(StandardCharsets.UTF_8));
        when(outboundMessageMock.getProperties()).thenReturn(new AMQP.BasicProperties());

        StepVerifier.create(handler.processMessage(resultMock)).verifyComplete();

        verify(resultMock).getOutboundMessage();
        verify(outboundMessageMock).getExchange();
        verify(outboundMessageMock).getRoutingKey();
        verify(outboundMessageMock).getBody();
        verify(outboundMessageMock).getProperties();
    }

    @Test
    void shouldCreateDefaultUnroutableMessageProcessorAndSubscribeToNotifier() {
        doNothing().when(unroutableMessageNotifier).listenToUnroutableMessages(any(UnroutableMessageHandler.class));

        var processor = rabbitMQConfig.defaultUnroutableMessageProcessor(unroutableMessageNotifier);

        assertThat(processor).isNotNull();
        verify(unroutableMessageNotifier).listenToUnroutableMessages(processor);
    }

    @Test
    void shouldReturnDefaultUnroutableMessageNotifier() {
        UnroutableMessageNotifier notifier = rabbitMQConfig.defaultUnroutableMessageNotifier();

        assertThat(notifier).isNotNull();
    }

    @Test
    void shouldSubscribeProcessorToNotifierWhenNotifierIsProvided() {
        doNothing().when(unroutableMessageNotifier).listenToUnroutableMessages(any(UnroutableMessageProcessor.class));

        var processor = rabbitMQConfig.defaultUnroutableMessageProcessor(unroutableMessageNotifier);

        assertThat(processor).isNotNull();
        verify(unroutableMessageNotifier).listenToUnroutableMessages(processor);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenNotifierIsNull() {
        assertThrows(NullPointerException.class, () -> rabbitMQConfig.defaultUnroutableMessageProcessor(null));
    }

    @Test
    void shouldReturnDefaultConnectionFactoryCustomizer() {
        ConnectionFactoryCustomizer customizer = rabbitMQConfig.defaultConnectionFactoryCustomizer();

        assertThat(customizer).isNotNull();
    }

    @Test
    void shouldReturnSameConnectionFactoryWhenCustomizing() {
        ConnectionFactoryCustomizer customizer = rabbitMQConfig.defaultConnectionFactoryCustomizer();
        ConnectionFactory originalFactory = new ConnectionFactory();
        AsyncProps asyncProps = new AsyncProps();

        ConnectionFactory result = customizer.customize(originalFactory, asyncProps);

        assertThat(result).isSameAs(originalFactory);
    }
}
