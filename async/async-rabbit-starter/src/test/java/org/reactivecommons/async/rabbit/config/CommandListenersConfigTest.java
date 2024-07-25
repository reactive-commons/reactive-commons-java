package org.reactivecommons.async.rabbit.config;

import com.rabbitmq.client.AMQP;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.BrokerConfigProps;
import org.reactivecommons.async.rabbit.listeners.ApplicationCommandListener;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.Receiver;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandListenersConfigTest {


    private final AsyncProps props = new AsyncProps();
    private CommandListenersConfig config = new CommandListenersConfig(props);
    private final ReactiveMessageListener listener = mock(ReactiveMessageListener.class);
    private final TopologyCreator creator = mock(TopologyCreator.class);
    private final HandlerResolver handlerResolver = mock(HandlerResolver.class);
    private final MessageConverter messageConverter = mock(MessageConverter.class);
    private final DiscardNotifier discardNotifier = mock(DiscardNotifier.class);
    private final CustomReporter customReporter = mock(CustomReporter.class);
    private final Receiver receiver = mock(Receiver.class);
    private final IBrokerConfigProps brokerConfigProps = new BrokerConfigProps("appName", props);

    @BeforeEach
    public void init() throws NoSuchFieldException, IllegalAccessException {
        final Field appName = CommandListenersConfig.class.getDeclaredField("appName");
        appName.setAccessible(true);
        appName.set(config, "queue");
        when(creator.bind(any(BindingSpecification.class))).thenReturn(Mono.just(mock(AMQP.Queue.BindOk.class)));
        when(creator.declare(any(ExchangeSpecification.class))).thenReturn(Mono.just(mock(AMQP.Exchange.DeclareOk.class)));
        when(creator.declareQueue(any(String.class), any())).thenReturn(Mono.just(mock(AMQP.Queue.DeclareOk.class)));
        when(listener.getTopologyCreator()).thenReturn(creator);
        when(receiver.consumeManualAck(any(String.class), any(ConsumeOptions.class))).thenReturn(Flux.never());
        when(listener.getReceiver()).thenReturn(receiver);
        when(listener.getMaxConcurrency()).thenReturn(20);
    }

    @Test
    void applicationCommandListener() {
        final ApplicationCommandListener commandListener = config.applicationCommandListener(
                listener,
                handlerResolver,
                messageConverter,
                discardNotifier,
                brokerConfigProps,
                customReporter
        );
        Assertions.assertThat(commandListener).isNotNull();
    }
}
