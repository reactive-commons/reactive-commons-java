package org.reactivecommons.async.rabbit.communications;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
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
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.Sender;
import reactor.test.StepVerifier;

import java.util.Date;
import java.util.HashMap;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReactiveMessageSenderTest {

    private final String sourceApplication = "TestApp";

    private ReactiveMessageSender messageSender;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private Sender sender;

    @Spy
    private final MessageConverter messageConverter = new RabbitJacksonMessageConverter(objectMapper);

    @BeforeEach
    public void init() {
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class))).then(invocation -> {
            final Flux<ReactiveMessageSender.MyOutboundMessage> argument = invocation.getArgument(0);
            return argument.map(myOutboundMessage -> {
                OutboundMessageResult<ReactiveMessageSender.MyOutboundMessage> outboundMessageResult = new OutboundMessageResult<>(myOutboundMessage, true);
                return outboundMessageResult;
            });
        });
        when(sender.send(any(Publisher.class))).thenReturn(Mono.empty());
        messageSender = new ReactiveMessageSender(sender, sourceApplication, messageConverter, null);
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

    @RequiredArgsConstructor
    @Getter
    private static class SomeClass {
        private final String id;
        private final String name;
        private final Date date;
    }

}