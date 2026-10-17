package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.BrokerConfigProps;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivecommons.async.rabbit.discard.RabbitMQDiscardProviderImpl;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RabbitMQDiscardProviderImplTest {
    @Mock
    private ConnectionFactoryCustomizer cfCustomizer;

    private AsyncProps props;
    private RabbitMQDiscardProviderImpl discardProvider;

    @BeforeEach
    void setUp() {
        props = new AsyncProps();
        props.setConnectionProperties(new RabbitProperties());
        IBrokerConfigProps brokerConfigProps = new BrokerConfigProps(props);
        props.setBrokerConfigProps(brokerConfigProps);
        discardProvider = new RabbitMQDiscardProviderImpl(props, new BrokerConfig(),
                new RabbitJacksonMessageConverter(new JsonMapper()), cfCustomizer);
    }

    @Test
    void shouldReturnTheSameNotifierWithoutCreatingAnySender() {
        // Act
        DiscardNotifier first = discardProvider.get();
        DiscardNotifier second = discardProvider.get();
        // Assert: nothing is built (no connection factory is customized) until a discard is notified
        assertThat(first).isNotNull().isSameAs(second);
        verify(cfCustomizer, times(0)).customize(any(ConnectionFactory.class), any(AsyncProps.class));
    }

    @Test
    void shouldPublishThroughTheSenderOfTheDefaultDomain() {
        // Arrange
        ReactiveMessageSender sender = mock(ReactiveMessageSender.class);
        when(sender.sendWithConfirm(any(), eq("domainEvents"), eq("order.created.dlq"), eq(Map.of()), anyBoolean()))
                .thenReturn(Mono.empty());
        discardProvider.useSender(sender);
        // Act
        Mono<Void> notification = discardProvider.get().notifyDiscard(discardedEvent());
        // Assert
        StepVerifier.create(notification).verifyComplete();
        verify(sender).sendWithConfirm(any(), eq("domainEvents"), eq("order.created.dlq"), eq(Map.of()),
                anyBoolean());
        verify(cfCustomizer, times(0)).customize(any(ConnectionFactory.class), any(AsyncProps.class));
    }

    @Test
    void shouldKeepTheFirstRegisteredSender() {
        // Arrange
        ReactiveMessageSender first = mock(ReactiveMessageSender.class);
        ReactiveMessageSender second = mock(ReactiveMessageSender.class);
        when(first.sendWithConfirm(any(), any(), any(), any(), anyBoolean())).thenReturn(Mono.empty());
        discardProvider.useSender(first);
        discardProvider.useSender(second);
        // Act
        StepVerifier.create(discardProvider.get().notifyDiscard(discardedEvent())).verifyComplete();
        // Assert
        verify(first).sendWithConfirm(any(), any(), any(), any(), anyBoolean());
        verify(second, times(0)).sendWithConfirm(any(), any(), any(), any(), anyBoolean());
    }

    private static RabbitMessage discardedEvent() {
        RabbitMessage.RabbitMessageProperties properties = new RabbitMessage.RabbitMessageProperties();
        properties.setContentType("application/json");
        byte[] body = "{\"name\":\"order.created\",\"eventId\":\"1\",\"data\":{\"id\":1}}"
                .getBytes(StandardCharsets.UTF_8);
        return new RabbitMessage(body, properties, "");
    }
}
