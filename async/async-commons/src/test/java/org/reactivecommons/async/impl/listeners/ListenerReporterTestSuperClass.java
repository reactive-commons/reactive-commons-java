package org.reactivecommons.async.impl.listeners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.parent.DiscardNotifier;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.Headers;
import org.reactivecommons.async.impl.communications.Message;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.impl.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.impl.converters.json.JacksonMessageConverter;
import org.reactivecommons.async.impl.ext.CustomErrorReporter;
import reactor.core.publisher.Flux;
import reactor.rabbitmq.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static reactor.core.publisher.Mono.empty;
import static reactor.core.publisher.Mono.just;

public abstract class ListenerReporterTestSuperClass {

    private final ObjectMapper mapper = new ObjectMapper();

    private final Receiver receiver = mock(Receiver.class);
    protected final TopologyCreator topologyCreator = mock(TopologyCreator.class);
    protected final DiscardNotifier discardNotifier = mock(DiscardNotifier.class);
    protected final MessageConverter messageConverter = new JacksonMessageConverter(new DefaultObjectMapperSupplier().get());
    protected final CustomErrorReporter errorReporter = mock(CustomErrorReporter.class);
    private GenericMessageListener messageListener;

    protected final Semaphore semaphore = new Semaphore(0);
    protected final Semaphore successSemaphore = new Semaphore(0);
    protected final ReactiveMessageListener reactiveMessageListener = new ReactiveMessageListener(receiver, topologyCreator);

    @BeforeEach
    public void init() {
        Mockito.when(topologyCreator.declare(any(ExchangeSpecification.class))).thenReturn(just(mock(AMQP.Exchange.DeclareOk.class)));
        Mockito.when(topologyCreator.declareDLQ(any(String.class), any(String.class), any(Integer.class), any(Optional.class))).thenReturn(just(mock(AMQP.Queue.DeclareOk.class)));
        Mockito.when(topologyCreator.declareQueue(any(String.class), any(String.class), any(Optional.class))).thenReturn(just(mock(AMQP.Queue.DeclareOk.class)));
        Mockito.when(topologyCreator.bind(any(BindingSpecification.class))).thenReturn(just(mock(AMQP.Queue.BindOk.class)));
    }

    protected void assertContinueAfterSendErrorToCustomReporter(HandlerRegistry handlerRegistry, Flux<AcknowledgableDelivery> source) throws InterruptedException {
        final HandlerResolver handlerResolver = createHandlerResolver(handlerRegistry);
        when(errorReporter.reportError(any(Throwable.class), any(Message.class), any(Object.class), any(Boolean.class)))
                .then(inv -> empty().doOnSuccess(o -> semaphore.release()));

        messageListener = createMessageListener(handlerResolver);

        Flux<AcknowledgableDelivery> messageFlux = source;
        when(receiver.consumeManualAck(Mockito.anyString(), any(ConsumeOptions.class))).thenReturn(messageFlux);

        messageListener.startListener();

        final boolean reported = semaphore.tryAcquire(1, TimeUnit.SECONDS);
        final boolean processed = successSemaphore.tryAcquire(1, TimeUnit.SECONDS);
        assertThat(reported).isTrue();
        assertThat(processed).isTrue();
    }

    protected void assertSendErrorToCustomReporter(HandlerRegistry handlerRegistry, Flux<AcknowledgableDelivery> source) throws InterruptedException {
        final HandlerResolver handlerResolver = createHandlerResolver(handlerRegistry);
        when(errorReporter.reportError(any(Throwable.class), any(Message.class), any(Object.class), any(Boolean.class)))
                .then(inv -> empty().doOnSuccess(o -> semaphore.release()));

        messageListener = createMessageListener(handlerResolver);

        Flux<AcknowledgableDelivery> messageFlux = source;
        when(receiver.consumeManualAck(Mockito.anyString(), any(ConsumeOptions.class))).thenReturn(messageFlux);

        messageListener.startListener();

        ArgumentCaptor<Throwable> throwableCaptor = ArgumentCaptor.forClass(Throwable.class);
        final boolean reported = semaphore.tryAcquire(1, TimeUnit.SECONDS);
        assertThat(reported).isTrue();
        verify(errorReporter).reportError(throwableCaptor.capture(), any(Message.class), any(Object.class), any(Boolean.class));
        assertThat(throwableCaptor.getValue().getMessage()).isEqualTo("testEx");
    }

    protected <T> Flux<AcknowledgableDelivery> createSource(Function<T, String> routeExtractor, T... events) {
        final List<AcknowledgableDelivery> list = Stream.of(events).map(value -> {
            final String data = valueAsString(value);
            final AMQP.BasicProperties props = new AMQP.BasicProperties().builder()
                    .messageId("unitTestMessage" + value)
                    .headers(Collections.singletonMap(Headers.SERVED_QUERY_ID, routeExtractor.apply(value)))
                    .build();

            final Envelope envelope = new Envelope(new Random().nextInt(), true, "exchange", routeExtractor.apply(value));
            final Delivery delivery = new Delivery(envelope, props, data.getBytes());
            return new AcknowledgableDelivery(delivery, new ChannelDummy(), null);
        }).collect(Collectors.toList());

        return Flux.fromIterable(new ArrayList<>(list));
    }

    protected abstract GenericMessageListener createMessageListener(final HandlerResolver handlerResolver);

    private HandlerResolver createHandlerResolver(final HandlerRegistry registry) {
        final Map<String, RegisteredEventListener<?>> eventHandlers = registry.getEventListeners().stream().collect(toMap(RegisteredEventListener::getPath, identity()));
        final Map<String, RegisteredEventListener<?>> notificationHandlers = registry.getEventNotificationListener().stream().collect(toMap(RegisteredEventListener::getPath, identity()));
        final Map<String, RegisteredEventListener<?>> dynamicEventsHandlers = registry.getEventNotificationListener().stream().collect(toMap(RegisteredEventListener::getPath, identity()));
        final Map<String, RegisteredQueryHandler<?, ?>> queryHandlers = registry.getHandlers().stream().collect(toMap(RegisteredQueryHandler::getPath, identity()));
        final Map<String, RegisteredCommandHandler<?>> commandHandlers = registry.getCommandHandlers().stream().collect(toMap(RegisteredCommandHandler::getPath, identity()));
        return new HandlerResolver(
                new ConcurrentHashMap<>(queryHandlers),
                new ConcurrentHashMap<>(eventHandlers),
                new ConcurrentHashMap<>(notificationHandlers),
                new ConcurrentHashMap<>(dynamicEventsHandlers),
                new ConcurrentHashMap<>(commandHandlers));
    }

    private Flux<AcknowledgableDelivery> createSource(DomainEvent<DummyMessage>... events) {
        final List<AcknowledgableDelivery> list = Stream.of(events).map(value -> {
            final String data = valueAsString(value);
            final AMQP.BasicProperties props = new AMQP.BasicProperties().builder()
                    .messageId("unitTestMessage" + value)
                    .headers(new HashMap<>())
                    .build();

            final Envelope envelope = new Envelope(new Random().nextInt(), true, "exchange", value.getName());
            final Delivery delivery = new Delivery(envelope, props, data.getBytes());
            return new AcknowledgableDelivery(delivery, new ChannelDummy(), null);
        }).collect(Collectors.toList());

        return Flux.fromIterable(new ArrayList<>(list));
    }

    protected String valueAsString(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}





