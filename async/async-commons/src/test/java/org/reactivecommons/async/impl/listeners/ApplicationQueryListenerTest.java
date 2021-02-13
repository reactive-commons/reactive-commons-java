package org.reactivecommons.async.impl.listeners;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.helpers.SampleClass;
import org.reactivecommons.async.helpers.TestStubs;
import org.reactivecommons.async.impl.DiscardNotifier;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.communications.Message;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.impl.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.impl.converters.json.JacksonMessageConverter;
import org.reactivecommons.async.impl.ext.CustomErrorReporter;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.Receiver;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.*;
import static reactor.core.publisher.Mono.*;

@ExtendWith(MockitoExtension.class)
public class ApplicationQueryListenerTest {
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
    private CustomErrorReporter errorReporter;

    @Mock
    private ReactiveMessageListener reactiveMessageListener;
    private GenericMessageListener genericMessageListener;

    @SuppressWarnings("rawtypes")
    @BeforeEach
    public void setUp() {
//        when(errorReporter.reportError(any(Throwable.class), any(Message.class), any(Object.class))).thenReturn(Mono.empty());
        when(reactiveMessageListener.getReceiver()).thenReturn(receiver);
        Optional<Integer> maxLengthBytes = Optional.of(Integer.MAX_VALUE);
        Map<String, RegisteredQueryHandler> handlers = new HashMap<>();
        handlers.put("queryDelegate", new RegisteredQueryHandler<Void, SampleClass>("queryDelegate",
                (from, message) -> empty(), SampleClass.class));
        QueryHandler<String, SampleClass> handler = (message) -> just("OK");
        handlers.put("queryDirect", new RegisteredQueryHandler<>("queryDirect",
                (from, message) -> handler.handle(message), SampleClass.class));
        HandlerResolver resolver = new HandlerResolver(handlers, null, null, null);
        genericMessageListener = new ApplicationQueryListener(reactiveMessageListener, "queue", resolver, sender,
                "directExchange", messageConverter, "replyExchange", false, 1, 100, maxLengthBytes, discardNotifier, errorReporter);
    }

    @Test
    public void shouldExecuteDelegateHandler() {
        Function<Message, Mono<Object>> handler = genericMessageListener.rawMessageHandler("queryDelegate");
        Message message = TestStubs.mockMessage();
        Mono<Object> result = handler.apply(message);

        StepVerifier.create(result)
                .verifyComplete();
    }

    @Test
    public void shouldExecuteDirectHandler() {
        Function<Message, Mono<Object>> handler = genericMessageListener.rawMessageHandler("queryDirect");
        Message message = TestStubs.mockMessage();
        Mono<Object> result = handler.apply(message);

        StepVerifier.create(result)
                .expectNext("OK")
                .verifyComplete();
    }

    @Test
    public void shouldHandleErrorWhenNoQueryHandler() {
        Function<Message, Mono<Object>> handler = genericMessageListener.rawMessageHandler("nonExistent");
        Message message = TestStubs.mockMessage();
        Mono<Object> result = handler.apply(message);

        StepVerifier.create(result)
                .verifyErrorMessage("Handler Not registered for Query: nonExistent");
    }

    @Test
    public void shouldNotRespondQueryEnrichPostProcess() {
        Message message = spy(TestStubs.mockMessage());
        Function<Mono<Object>, Mono<Object>> handler = genericMessageListener.enrichPostProcess(message);
        Mono<Object> result = handler.apply(empty());

        StepVerifier.create(result)
                .verifyComplete();

        verify(message, times(0)).getProperties();
    }

    @Test
    public void shouldRespondQueryEnrichPostProcess() {
        Message message = spy(TestStubs.mockMessage());
        Function<Mono<Object>, Mono<Object>> handler = genericMessageListener.enrichPostProcess(message);
        Mono<Object> result = handler.apply(just("OK"));
        when(sender.sendNoConfirm(any(), anyString(), anyString(), anyMap(), anyBoolean())).thenReturn(empty());

        StepVerifier.create(result)
                .verifyComplete();

        verify(message, times(2)).getProperties();
    }

    @Test
    public void shouldHandleErrorWhenEnrichPostProcessSignalError() {
        Message message = TestStubs.mockMessage();
        Function<Mono<Object>, Mono<Object>> handler = genericMessageListener.enrichPostProcess(message);
        String errorMessage = "Error";
        Mono<Object> result = handler.apply(error(new RuntimeException(errorMessage)));

        StepVerifier.create(result)
                .verifyErrorMessage(errorMessage);
    }

}





