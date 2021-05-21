package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.Queue.BindOk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.rabbit.DynamicRegistryImp;
import org.reactivecommons.async.rabbit.HandlerResolver;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.BindingSpecification;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static reactor.core.publisher.Mono.just;


@ExtendWith(MockitoExtension.class)
class DynamicRegistryImpTest {

    private HandlerResolver resolver;

    @Mock
    private TopologyCreator topologyCreator;

    @Mock
    private IBrokerConfigProps props;

    private DynamicRegistryImp dynamicRegistry;


    @BeforeEach
    @SuppressWarnings("rawtypes")
    void setUp() {
        Map<String, RegisteredCommandHandler<?>> commandHandlers = new ConcurrentHashMap<>();
        Map<String, RegisteredEventListener<?>> eventListeners = new ConcurrentHashMap<>();
        Map<String, RegisteredEventListener<?>> notificationEventListeners = new ConcurrentHashMap<>();
        Map<String, RegisteredEventListener<?>> dynamicEventsHandlers = new ConcurrentHashMap<>();
        Map<String, RegisteredQueryHandler<?, ?>> queryHandlers = new ConcurrentHashMap<>();
        resolver = new HandlerResolver(queryHandlers, eventListeners, notificationEventListeners,
                dynamicEventsHandlers, commandHandlers);
        when(props.getDomainEventsExchangeName()).thenReturn("domainEx");
        when(props.getEventsQueue()).thenReturn("events.queue");
        dynamicRegistry = new DynamicRegistryImp(resolver, topologyCreator, props);
    }

    @Test
    void registerEventListener() {
        when(topologyCreator.bind(any())).thenReturn(just(mock(BindOk.class)));
        dynamicRegistry.listenEvent("event1", message -> Mono.empty(), Long.class);

        final RegisteredEventListener<Object> listener = resolver.getEventListener("event1");
        assertThat(listener).isNotNull();
    }

    @Test
    void declareBindingWhenRegisterEventListener() {
        ArgumentCaptor<BindingSpecification> captor = ArgumentCaptor.forClass(BindingSpecification.class);
        when(topologyCreator.bind(any())).thenReturn(just(mock(BindOk.class)));

        dynamicRegistry.listenEvent("event1", message -> Mono.empty(), Long.class);

        verify(topologyCreator).bind(captor.capture());
        final BindingSpecification binding = captor.getValue();
        assertThat(binding.getQueue()).isEqualTo(props.getEventsQueue());
        assertThat(binding.getExchange()).isEqualTo(props.getDomainEventsExchangeName());
        assertThat(binding.getRoutingKey()).isEqualTo("event1");
    }

    @Test
    void subscribeToResultWhenRegisterEventListener() {
        PublisherProbe<BindOk> probe = PublisherProbe.of(just(mock(BindOk.class)));
        when(topologyCreator.bind(any())).thenReturn(probe.mono());

        Mono<Void> result = dynamicRegistry.listenEvent("event1", message -> Mono.empty(), Long.class);

        StepVerifier.create(result).verifyComplete();
        probe.assertWasSubscribed();

    }

    @Test
    void shouldBindDomainEventsToEventsQueueUsingEventName() {
        ArgumentCaptor<BindingSpecification> bindingSpecificationCaptor =
                ArgumentCaptor.forClass(BindingSpecification.class);

        PublisherProbe<AMQP.Queue.BindOk> topologyCreatorProbe = PublisherProbe.empty();

        when(topologyCreator.bind(bindingSpecificationCaptor.capture()))
                .thenReturn(topologyCreatorProbe.mono());

        String eventName = "a.b.c";
        BindingSpecification bindingSpecification =
                BindingSpecification.binding("domainEx", eventName, "events.queue");

        StepVerifier.create(dynamicRegistry.startListeningEvent(eventName))
                .expectComplete()
                .verify();

        assertThat(bindingSpecificationCaptor.getValue())
                .usingRecursiveComparison()
                .isEqualTo(bindingSpecification);

        topologyCreatorProbe.assertWasSubscribed();
    }

    @Test
    void shouldUnbindDomainEventsToEventsQueueUsingEventName() {
        ArgumentCaptor<BindingSpecification> bindingSpecificationCaptor =
                ArgumentCaptor.forClass(BindingSpecification.class);

        PublisherProbe<AMQP.Queue.UnbindOk> topologyCreatorProbe = PublisherProbe.empty();

        when(topologyCreator.unbind(bindingSpecificationCaptor.capture()))
                .thenReturn(topologyCreatorProbe.mono());

        String eventName = "a.b.c";
        BindingSpecification bindingSpecification =
                BindingSpecification.binding("domainEx", eventName, "events.queue");

        StepVerifier.create(dynamicRegistry.stopListeningEvent(eventName))
                .expectComplete()
                .verify();

        assertThat(bindingSpecificationCaptor.getValue())
                .usingRecursiveComparison()
                .isEqualTo(bindingSpecification);

        topologyCreatorProbe.assertWasSubscribed();
    }


}
