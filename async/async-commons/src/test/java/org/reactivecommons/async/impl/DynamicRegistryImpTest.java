package org.reactivecommons.async.impl;

import com.rabbitmq.client.AMQP.Queue.BindOk;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import org.reactivecommons.async.impl.config.IBrokerConfigProps;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.BindingSpecification;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static reactor.core.publisher.Mono.just;

@RunWith(MockitoJUnitRunner.class)
public class DynamicRegistryImpTest {

    private HandlerResolver resolver;

    @Mock
    private TopologyCreator topologyCreator;

    @Mock
    private IBrokerConfigProps props;

    private DynamicRegistryImp registry;

    @Before
    public void init() {
        Map<String, RegisteredCommandHandler> commandHandlers = new ConcurrentHashMap<>();
        Map<String, RegisteredEventListener> eventListeners = new ConcurrentHashMap<>();
        Map<String, RegisteredQueryHandler> queryHandlers = new ConcurrentHashMap<>();
        Map<String, RegisteredEventListener> notificationEventListeners = new ConcurrentHashMap<>();
        resolver = new HandlerResolver(queryHandlers, eventListeners, commandHandlers,notificationEventListeners);
        when(props.getDomainEventsExchangeName()).thenReturn("domainEx");
        when(props.getEventsQueue()).thenReturn("events.queue");
        registry = new DynamicRegistryImp(resolver, topologyCreator, props);
    }

    @Test
    public void registerEventListener() {
        when(topologyCreator.bind(any())).thenReturn(just(mock(BindOk.class)));
        registry.listenEvent("event1", message -> Mono.empty(), Long.class);

        final RegisteredEventListener<Object> listener = resolver.getEventListener("event1");
        assertThat(listener).isNotNull();
    }

    @Test
    public void declareBindingWhenRegisterEventListener() {
        ArgumentCaptor<BindingSpecification> captor = ArgumentCaptor.forClass(BindingSpecification.class);
        when(topologyCreator.bind(any())).thenReturn(just(mock(BindOk.class)));

        registry.listenEvent("event1", message -> Mono.empty(), Long.class);

        verify(topologyCreator).bind(captor.capture());
        final BindingSpecification binding = captor.getValue();
        assertThat(binding.getQueue()).isEqualTo(props.getEventsQueue());
        assertThat(binding.getExchange()).isEqualTo(props.getDomainEventsExchangeName());
        assertThat(binding.getRoutingKey()).isEqualTo("event1");
    }

    @Test
    public void subscribeToResultWhenRegisterEventListener() {
        PublisherProbe<BindOk> probe = PublisherProbe.of(just(mock(BindOk.class)));
        when(topologyCreator.bind(any())).thenReturn(probe.mono());

        Mono<Void> result = registry.listenEvent("event1", message -> Mono.empty(), Long.class);

        StepVerifier.create(result).verifyComplete();
        probe.assertWasSubscribed();

    }



}