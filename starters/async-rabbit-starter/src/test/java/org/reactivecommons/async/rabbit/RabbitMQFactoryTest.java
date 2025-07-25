package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.spring.RabbitPropertiesBase;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RabbitMQFactoryTest {

    @Mock
    private RabbitJacksonMessageConverter converter;

    @Mock
    private RabbitProperties rabbitProperties;

    @Mock
    private AsyncProps asyncProps;

    @InjectMocks
    private RabbitMQFactory factory;

    @Nested
    class CreateMessageSenderFromProperties {

        @Test
        void shouldCreateReactiveMessageSenderWithValidProperties() {
            when(rabbitProperties.getCache()).thenReturn(new RabbitPropertiesBase.Cache());
            when(asyncProps.getConnectionProperties()).thenReturn(rabbitProperties);

            ReactiveMessageSender sender = factory.createMessageSender(asyncProps);
            assertNotNull(sender);
        }

        @Test
        @DisplayName("should throw NullPointerException when properties are null")
        void shouldThrowExceptionWhenPropertiesAreNull() {
            AsyncProps props = null;

            assertThrows(NullPointerException.class, () -> factory.createMessageSender(props));
        }
    }
}