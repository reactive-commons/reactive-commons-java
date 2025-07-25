package org.reactivecommons.async.rabbit.communications;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.SendOptions;
import reactor.rabbitmq.Sender;
import reactor.test.StepVerifier;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class ReactiveMessageSenderTest {

    private ReactiveMessageSender messageSender;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private Sender sender;

    @Spy
    private final MessageConverter messageConverter = new RabbitJacksonMessageConverter(objectMapper);

    @Mock
    private final SendOptions sendOptions = new SendOptions();

    @Mock
    private UnroutableMessageHandler unroutableMessageHandler;

    @BeforeEach
    void init() {
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            final Flux<MyOutboundMessage> argument = invocation.getArgument(0);
            return argument
                    .map(myOutboundMessage -> new OutboundMessageResult<>(myOutboundMessage, true));
        });
        when(sender.send(any(Publisher.class))).then(invocation -> {
            final Flux<OutboundMessage> argument = invocation.getArgument(0);
            argument.subscribe(); // Suscribirse para inicializar el sink
            return Mono.empty();
        });
        String sourceApplication = "TestApp";

        messageSender = new ReactiveMessageSender(sender, sourceApplication, messageConverter, null, false, unroutableMessageHandler);
    }

    @Test
    void shouldCallUnroutableMessageHandlerWhenMessageIsReturned() {
        String sourceApplication = "TestApp";
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            Flux<MyOutboundMessage> argument = invocation.getArgument(0);
            return argument.map(msg -> new OutboundMessageResult<>(msg, false, true));
        });

        messageSender = new ReactiveMessageSender(
                sender, sourceApplication, messageConverter, null, true, unroutableMessageHandler
        );
        SomeClass messageContent = new SomeClass("id", "name", new Date());

        messageSender.sendWithConfirm(messageContent, "exchange", "rkey", new HashMap<>(), true)
                .subscribe();

        verify(unroutableMessageHandler, timeout(1000).times(1))
                .processMessage(any(OutboundMessageResult.class));
    }


    @Test
    void shouldSendMessageSuccessfully() {
        Object message = new SomeClass("id", "name", new Date());
        String exchange = "test.exchange";
        String routingKey = "test.routingKey";
        Map<String, Object> headers = new HashMap<>();

        Mono<Void> result = messageSender.sendMessage(message, exchange, routingKey, headers);

        StepVerifier.create(result).verifyComplete();
    }

    @Test
    void shouldSendBatchWithConfirmSuccessfully() {
        Flux<SomeClass> messages = Flux.just(
                new SomeClass("id1", "name1", new Date()),
                new SomeClass("id2", "name2", new Date())
        );
        String exchange = "test.exchange";
        String routingKey = "test.routingKey";
        Map<String, Object> headers = new HashMap<>();

        OutboundMessageResult<OutboundMessage> result1 = new OutboundMessageResult<>(null, true);
        OutboundMessageResult<OutboundMessage> result2 = new OutboundMessageResult<>(null, true);

        when(sender.sendWithPublishConfirms(any(Publisher.class)))
                .thenReturn(Flux.just(result1, result2));

        Flux<OutboundMessageResult> resultFlux = messageSender.sendWithConfirmBatch(messages, exchange, routingKey, headers, true);

        StepVerifier.create(resultFlux).verifyComplete();
    }

    @Test
    void sendWithConfirmEmptyNullMessage() {
        final Mono<Void> voidMono = messageSender.sendWithConfirm(null, "exchange", "rkey", new HashMap<>(), true);

        StepVerifier.create(voidMono).verifyComplete();
    }

    @Test
    void sendWithConfirmSomeMessage() {
        SomeClass some = new SomeClass("42", "Daniel", new Date());
        final Mono<Void> voidMono = messageSender.sendWithConfirm(some, "exchange", "rkey", new HashMap<>(), true);

        StepVerifier.create(voidMono).verifyComplete();
    }

    private record SomeClass(String id, String name, Date date) {
    }

}