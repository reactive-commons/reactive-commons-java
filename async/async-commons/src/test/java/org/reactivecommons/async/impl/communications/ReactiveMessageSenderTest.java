package org.reactivecommons.async.impl.communications;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.reactivecommons.async.impl.converters.JacksonMessageConverter;
import org.reactivecommons.async.impl.converters.MessageConverter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.Sender;
import reactor.test.StepVerifier;

import java.util.Date;
import java.util.HashMap;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ReactiveMessageSenderTest {

    private final String sourceApplication = "TestApp";

    private ReactiveMessageSender messageSender;

    private OutboundMessageResult result = new OutboundMessageResult(null, true);

    @Mock
    private Sender sender;

    @Spy
    private final MessageConverter messageConverter = new JacksonMessageConverter(new ObjectMapper());

    @Before
    public void init() {
        messageSender = new ReactiveMessageSender(sender, sourceApplication, messageConverter, null);
    }


    @Test
    public void sendWithConfirmEmptyNullMessage() {
        when(sender.sendWithPublishConfirms(any())).thenReturn(Flux.just(result));

        final Mono<Void> voidMono = messageSender.sendWithConfirm(null, "exchange", "rkey", new HashMap<>());

        StepVerifier.create(voidMono).verifyComplete();
    }

    @Test
    public void sendWithConfirmSomeMessage() {
        when(sender.sendWithPublishConfirms(any())).thenReturn(Flux.just(result));

        SomeClass some = new SomeClass("42", "Daniel", new Date());
        final Mono<Void> voidMono = messageSender.sendWithConfirm(some, "exchange", "rkey", new HashMap<>());

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