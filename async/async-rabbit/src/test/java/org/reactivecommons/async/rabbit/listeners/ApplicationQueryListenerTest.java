package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.impl.ValueReader;
import com.rabbitmq.client.impl.ValueWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.exceptions.MissingHandlerException;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.helpers.SampleClass;
import org.reactivecommons.async.helpers.TestStubs;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.Receiver;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.commons.Headers.CORRELATION_ID;
import static org.reactivecommons.async.commons.Headers.REPLY_ID;
import static org.reactivecommons.async.commons.Headers.REPLY_DEADLINE_MILLIS;
import static org.reactivecommons.async.commons.Headers.REPLY_TIMEOUT_MILLIS;
import static org.reactivecommons.async.commons.Headers.SERVED_QUERY_ID;
import static reactor.core.publisher.Mono.empty;
import static reactor.core.publisher.Mono.error;
import static reactor.core.publisher.Mono.just;

@ExtendWith(MockitoExtension.class)
class ApplicationQueryListenerTest {

    private static final JsonMapper OBJECT_MAPPER = new JsonMapper();

    private final MessageConverter messageConverter =
            new RabbitJacksonMessageConverter(new DefaultObjectMapperSupplier().get());


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

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ApplicationQueryListener applicationQueryListener;

    @BeforeEach
    void setUp() {
        when(reactiveMessageListener.receiver()).thenReturn(receiver);
        when(reactiveMessageListener.meterRegistry()).thenReturn(registry);
        Optional<Integer> maxLengthBytes = Optional.of(Integer.MAX_VALUE);
        HandlerResolver resolver = getHandlerResolver();
        applicationQueryListener = new ApplicationQueryListener(reactiveMessageListener, "queue", resolver, sender,
                "directExchange", messageConverter, "replyExchange", false,
                true, 1, 100, maxLengthBytes, true, discardNotifier, errorReporter);
    }

    private static HandlerResolver getHandlerResolver() {
        Map<String, RegisteredQueryHandler<?, ?>> handlers = new HashMap<>();
        handlers.put("queryDelegate", new RegisteredQueryHandler<Void, SampleClass>("queryDelegate",
                (from, message) -> empty(), SampleClass.class));
        QueryHandler<String, SampleClass> handler = message -> just("OK");
        handlers.put("queryDirect", new RegisteredQueryHandler<>("queryDirect",
                (from, message) -> handler.handle(message), SampleClass.class));
        return new HandlerResolver(handlers, null, null, null, null, null, null);
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
        // not cached as a failing handler, and recognized by the listener as a message to discard
        assertThatThrownBy(() -> applicationQueryListener.rawMessageHandler("nonExistent"))
                .isInstanceOf(MissingHandlerException.class)
                .hasMessage("No handler registered for query nonExistent");
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
    void shouldUseBaseHandleIfNoTimeoutMetadataProvided() {
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

        StepVerifier.create(applicationQueryListener.handle(acknowledgableDelivery, System.nanoTime()))
                .expectNext(acknowledgableDelivery)
                .verifyComplete();
    }

    @Test
    void shouldDiscardMessageIfItIsTimeout() {
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

        StepVerifier.create(applicationQueryListener.handle(acknowledgableDelivery, System.nanoTime()))
                .verifyComplete();

        assertThat(counter("reactive_commons.consumer.query.discarded")).isEqualTo(1.0);
    }

    @Test
    void shouldHandleMessageIfHasTimeAvailable() {
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

        StepVerifier.create(applicationQueryListener.handle(acknowledgableDelivery, System.nanoTime()))
                .expectNext(acknowledgableDelivery)
                .verifyComplete();
    }

    /**
     * The gateway publishes the reply timeout as a {@code long} ({@code Duration.toMillis()}); the RabbitMQ client
     * encodes it as an AMQP long and decodes it back as a {@link Long}, never as an {@link Integer}.
     */
    @Test
    void timeoutHeaderPublishedByTheGatewayArrivesAsALong() throws Exception {
        Map<String, Object> published = Map.of(REPLY_TIMEOUT_MILLIS, Duration.ofSeconds(15).toMillis());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new ValueWriter(new DataOutputStream(bytes)).writeTable(published);
        Map<String, Object> received = new ValueReader(new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))).readTable();

        assertThat(received.get(REPLY_TIMEOUT_MILLIS)).isInstanceOf(Long.class).isEqualTo(15_000L);
    }

    @Test
    void shouldHandleQueryWhoseTimeoutHeaderIsALong() {
        AcknowledgableDelivery delivery = queryDelivery(new Date(), 15_000L);
        when(sender.sendNoConfirm(eq("OK"), eq("replyExchange"), eq("asdf1234"), any(), eq(false)))
                .thenReturn(Mono.empty());

        StepVerifier.create(Mono.defer(() -> applicationQueryListener.handle(delivery, System.nanoTime())))
                .expectNext(delivery)
                .verifyComplete();
    }

    @Test
    void shouldDiscardExpiredQueryWhoseTimeoutHeaderIsALong() {
        AcknowledgableDelivery delivery = queryDelivery(Date.from(Instant.now().minusSeconds(20)), 15_000L);

        StepVerifier.create(Mono.defer(() -> applicationQueryListener.handle(delivery, System.nanoTime())))
                .verifyComplete();

        verifyNoInteractions(sender);
        assertThat(counter("reactive_commons.consumer.query.discarded")).isEqualTo(1.0);
    }

    @Test
    void shouldHandleQueryWithoutTimeoutWhenTheTimeoutHeaderIsNotANumber() {
        AcknowledgableDelivery delivery = queryDelivery(Date.from(Instant.now().minusSeconds(20)), "not-a-number");
        when(sender.sendNoConfirm(eq("OK"), eq("replyExchange"), eq("asdf1234"), any(), eq(false)))
                .thenReturn(Mono.empty());

        StepVerifier.create(Mono.defer(() -> applicationQueryListener.handle(delivery, System.nanoTime())))
                .expectNext(delivery)
                .verifyComplete();

        assertThat(counter("reactive_commons.consumer.query.invalid.timeout_header")).isEqualTo(1.0);
    }

    @Test
    void anInvalidTimeoutHeaderIsCountedOnceEvenWhenTheQueryFails() {
        AcknowledgableDelivery delivery = queryDelivery(Date.from(Instant.now().minusSeconds(20)), "not-a-number");
        applicationQueryListener.handle(delivery, System.nanoTime()).subscribe(ignored -> { }, ignored -> { });

        // on a failure the listener also checks whether retrying is useless, which reads the same header
        assertThat(applicationQueryListener.retryIsUseless(delivery)).isFalse();

        assertThat(counter("reactive_commons.consumer.query.invalid.timeout_header")).isEqualTo(1.0);
    }

    /**
     * The AMQP timestamp only has a resolution of one second: the deadline in milliseconds published by the gateway
     * is used instead whenever it is present.
     */
    @Test
    void theDeadlineHeaderIsUsedInsteadOfTheTimestamp() {
        AcknowledgableDelivery expiredByTimestamp = queryDelivery(Date.from(Instant.now().minusSeconds(20)), 15_000L,
                Instant.now().plusSeconds(5).toEpochMilli());
        when(sender.sendNoConfirm(eq("OK"), eq("replyExchange"), eq("asdf1234"), any(), eq(false)))
                .thenReturn(Mono.empty());

        StepVerifier.create(Mono.defer(() -> applicationQueryListener.handle(expiredByTimestamp, System.nanoTime())))
                .expectNext(expiredByTimestamp)
                .verifyComplete();
        assertThat(applicationQueryListener.retryIsUseless(expiredByTimestamp)).isFalse();

        AcknowledgableDelivery expiredByDeadline = queryDelivery(new Date(), 15_000L,
                Instant.now().minusMillis(1).toEpochMilli());

        StepVerifier.create(Mono.defer(() -> applicationQueryListener.handle(expiredByDeadline, System.nanoTime())))
                .verifyComplete();
        assertThat(applicationQueryListener.retryIsUseless(expiredByDeadline)).isTrue();
        assertThat(counter("reactive_commons.consumer.query.discarded")).isEqualTo(1.0);
    }

    @Test
    void aReplyThatCannotBePublishedIsCounted() {
        Message message = TestStubs.mockMessage();
        when(sender.sendNoConfirm(any(), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(Mono.error(new IllegalStateException("broker blocked")));
        Function<Mono<Object>, Mono<Object>> handler = applicationQueryListener.enrichPostProcess(message);

        StepVerifier.create(handler.apply(just("OK")))
                .verifyComplete(); // the failure is still swallowed: the query must not run again

        assertThat(counter("reactive_commons.consumer.query.reply.failed")).isEqualTo(1.0);
    }

    private double counter(String name) {
        return registry.find(name).tags("domain", "", "queue", "queue").counter().count();
    }

    /**
     * {@code handle} runs inside the listener {@code flatMap}: throwing there (instead of returning a failed Mono)
     * terminates the whole consumer, requeues every unacknowledged query and re-subscribes, in a loop.
     */
    @Test
    void handleNeverThrowsSynchronouslyWhenTheTimeoutMetadataIsPresent() {
        AcknowledgableDelivery delivery = queryDelivery(new Date(), 15_000L);
        when(sender.sendNoConfirm(eq("OK"), eq("replyExchange"), eq("asdf1234"), any(), eq(false)))
                .thenReturn(Mono.empty());

        Mono<AcknowledgableDelivery> result = applicationQueryListener.handle(delivery, System.nanoTime());

        StepVerifier.create(result).expectNext(delivery).verifyComplete();
    }

    private AcknowledgableDelivery queryDelivery(Date timestamp, Object replyTimeoutMillis) {
        return queryDelivery(timestamp, replyTimeoutMillis, null);
    }

    private AcknowledgableDelivery queryDelivery(Date timestamp, Object replyTimeoutMillis, Long replyDeadlineMillis) {
        String data = OBJECT_MAPPER.writeValueAsString(new AsyncQuery<>("queryDirect", new DummyMessage()));
        Map<String, Object> headers = buildQueryHeaders("queryDirect", false);
        headers.put(REPLY_TIMEOUT_MILLIS, replyTimeoutMillis);
        if (replyDeadlineMillis != null) {
            headers.put(REPLY_DEADLINE_MILLIS, replyDeadlineMillis);
        }
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .timestamp(timestamp)
                .headers(headers)
                .build();
        Envelope envelope = new Envelope(0L, true, "directMessages", "app.query");
        return new AcknowledgableDelivery(new Delivery(envelope, props, data.getBytes()), null, null);
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





