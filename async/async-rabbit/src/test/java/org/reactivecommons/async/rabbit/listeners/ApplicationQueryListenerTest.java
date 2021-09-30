package org.reactivecommons.async.rabbit.listeners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.helpers.SampleClass;
import org.reactivecommons.async.helpers.TestStubs;
import org.reactivecommons.async.rabbit.HandlerResolver;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.rabbit.converters.json.JacksonMessageConverter;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.Receiver;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.*;
import static org.reactivecommons.async.commons.Headers.*;
import static reactor.core.publisher.Mono.*;

@ExtendWith(MockitoExtension.class)
class ApplicationQueryListenerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final MessageConverter messageConverter =
            new JacksonMessageConverter(new DefaultObjectMapperSupplier().get());


    @Mock
    private Receiver receiver;
    @Mock
    private ReactiveMessageSender sender;
    @Mock
    private DiscardNotifier discardNotifier;
    @Mock
    private TopologyCreator topologyCreator;

    @Mock
    private CustomReporter errorReporter;

    @Mock
    private ReactiveMessageListener reactiveMessageListener;

    private ApplicationQueryListener applicationQueryListener;

    @SuppressWarnings("rawtypes")
    @BeforeEach
    public void setUp() {
//        when(errorReporter.reportError(any(Throwable.class), any(Message.class), any(Object.class))).thenReturn(Mono.empty());
        when(reactiveMessageListener.getReceiver()).thenReturn(receiver);
        Optional<Integer> maxLengthBytes = Optional.of(Integer.MAX_VALUE);
        Map<String, RegisteredQueryHandler<?, ?>> handlers = new HashMap<>();
        handlers.put("queryDelegate", new RegisteredQueryHandler<Void, SampleClass>("queryDelegate",
                (from, message) -> empty(), SampleClass.class));
        QueryHandler<String, SampleClass> handler = (message) -> just("OK");
        handlers.put("queryDirect", new RegisteredQueryHandler<>("queryDirect",
                (from, message) -> handler.handle(message), SampleClass.class));
        HandlerResolver resolver = new HandlerResolver(handlers, null, null, null);
        applicationQueryListener = new ApplicationQueryListener(reactiveMessageListener, "queue", resolver, sender,
                "directExchange", messageConverter, "replyExchange", false,
                1, 100, maxLengthBytes, true, discardNotifier, errorReporter);
    }

    @Test
    void shouldExecuteDelegateHandler() {
        Function<Message, Mono<Object>> handler = applicationQueryListener.rawMessageHandler("queryDelegate");
        Message message = TestStubs.mockMessage();
        Mono<Object> result = handler.apply(message);

        StepVerifier.create(result)
                .verifyComplete();
    }

    @Test
    void shouldExecuteDirectHandler() {
        Function<Message, Mono<Object>> handler = applicationQueryListener.rawMessageHandler("queryDirect");
        Message message = TestStubs.mockMessage();
        Mono<Object> result = handler.apply(message);

        StepVerifier.create(result)
                .expectNext("OK")
                .verifyComplete();
    }

    @Test
    void shouldHandleErrorWhenNoQueryHandler() {
        Function<Message, Mono<Object>> handler = applicationQueryListener.rawMessageHandler("nonExistent");
        Message message = TestStubs.mockMessage();
        Mono<Object> result = handler.apply(message);

        StepVerifier.create(result)
                .verifyErrorMessage("Handler Not registered for Query: nonExistent");
    }

    @Test
    void shouldNotRespondQueryEnrichPostProcess() {
        Message message = spy(TestStubs.mockMessage());
        Function<Mono<Object>, Mono<Object>> handler = applicationQueryListener.enrichPostProcess(message);
        Mono<Object> result = handler.apply(empty());

        StepVerifier.create(result)
                .verifyComplete();

        verify(message, times(0)).getProperties();
    }

    @Test
    void shouldRespondQueryEnrichPostProcess() {
        Message message = spy(TestStubs.mockMessage());
        Function<Mono<Object>, Mono<Object>> handler = applicationQueryListener.enrichPostProcess(message);
        Mono<Object> result = handler.apply(just("OK"));
        when(sender.sendNoConfirm(any(), anyString(), anyString(), anyMap(), anyBoolean())).thenReturn(empty());

        StepVerifier.create(result)
                .verifyComplete();

        verify(message, times(2)).getProperties();
    }

    @Test
    void shouldHandleErrorWhenEnrichPostProcessSignalError() {
        Message message = TestStubs.mockMessage();
        Function<Mono<Object>, Mono<Object>> handler = applicationQueryListener.enrichPostProcess(message);
        String errorMessage = "Error";
        Mono<Object> result = handler.apply(error(new RuntimeException(errorMessage)));

        StepVerifier.create(result)
                .verifyErrorMessage(errorMessage);
    }

    @Test
    void shouldUseBaseHandleIfNoTimeoutMetadataProvided() throws JsonProcessingException {
        String queryName = "queryDirect";

        AsyncQuery<DummyMessage> query = new AsyncQuery<>(queryName, new DummyMessage());
        String data = OBJECT_MAPPER.writeValueAsString(query);

        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .timestamp(new Date())
                .headers(buildQueryHeaders(queryName, false))
                .build();

        Envelope envelope = new Envelope(0L, true, "directMessages", "app.query");
        Delivery delivery = new Delivery(envelope, props, data.getBytes());

        AcknowledgableDelivery acknowledgableDelivery = new AcknowledgableDelivery(delivery, null, null);

        when(sender.sendNoConfirm(eq("OK"), eq("replyExchange"), eq("asdf1234"), any(), eq(false)))
                .thenReturn(Mono.empty());

        StepVerifier.create(applicationQueryListener.handle(acknowledgableDelivery, Instant.now()))
                .expectNext(acknowledgableDelivery)
                .verifyComplete();
    }

    @Test
    void shouldDiscardMessageIfItIsTimeout() throws JsonProcessingException {
        String queryName = "queryDirect";
        int timeoutMillis = 15000;

        AsyncQuery<DummyMessage> query = new AsyncQuery<>(queryName, new DummyMessage());
        String data = OBJECT_MAPPER.writeValueAsString(query);

        Instant nowMinusMillis = Instant.now()
                .minusMillis(timeoutMillis);

        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .timestamp(Date.from(nowMinusMillis))
                .headers(buildQueryHeaders(queryName, true))
                .build();

        Envelope envelope = new Envelope(0L, true, "directMessages", "app.query");
        Delivery delivery = new Delivery(envelope, props, data.getBytes());

        AcknowledgableDelivery acknowledgableDelivery = new AcknowledgableDelivery(delivery, null, null);

        verifyNoInteractions(sender);

        StepVerifier.create(applicationQueryListener.handle(acknowledgableDelivery, Instant.now()))
                .verifyComplete();
    }

    @Test
    void shouldHandleMessageIfHasTimeAvailable() throws JsonProcessingException {
        String queryName = "queryDirect";

        AsyncQuery<DummyMessage> query = new AsyncQuery<>(queryName, new DummyMessage());
        String data = OBJECT_MAPPER.writeValueAsString(query);

        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .timestamp(new Date())
                .headers(buildQueryHeaders(queryName, true))
                .build();

        Envelope envelope = new Envelope(0L, true, "directMessages", "app.query");
        Delivery delivery = new Delivery(envelope, props, data.getBytes());

        AcknowledgableDelivery acknowledgableDelivery = new AcknowledgableDelivery(delivery, null, null);

        when(sender.sendNoConfirm(eq("OK"), eq("replyExchange"), eq("asdf1234"), any(), eq(false)))
                .thenReturn(Mono.empty());

        StepVerifier.create(applicationQueryListener.handle(acknowledgableDelivery, Instant.now()))
                .expectNext(acknowledgableDelivery)
                .verifyComplete();
    }

    private Map<String, Object> buildQueryHeaders(String queryName, Boolean withTimeout) {
        int timeoutMillis = 15000;

        Map<String, Object> headers = new HashMap<>();
        headers.put(SERVED_QUERY_ID, queryName);
        headers.put(REPLY_ID, "asdf1234");
        headers.put(CORRELATION_ID, "lkj987");

        if (withTimeout) {
            headers.put(REPLY_TIMEOUT_MILLIS, timeoutMillis);
        }

        return headers;
    }

}





