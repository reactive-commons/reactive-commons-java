package org.reactivecommons.async.rabbit.communications;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnroutableMessageProcessorTest {

    @Mock
    private OutboundMessageResult<OutboundMessage> messageResult;

    @Mock
    private OutboundMessage outboundMessage;

    @InjectMocks
    private UnroutableMessageProcessor unroutableMessageProcessor;


    @Test
    void logsUnroutableMessageDetails() {
        when(messageResult.outboundMessage()).thenReturn(outboundMessage);
        when(outboundMessage.getExchange()).thenReturn("test-exchange");
        when(outboundMessage.getRoutingKey()).thenReturn("test-routingKey");
        when(outboundMessage.getBody()).thenReturn("test-body".getBytes(StandardCharsets.UTF_8));
        when(outboundMessage.getProperties()).thenReturn(null);

        StepVerifier.create(unroutableMessageProcessor.processMessage(messageResult))
                .verifyComplete();

        verify(messageResult).outboundMessage();
        verify(outboundMessage).getExchange();
        verify(outboundMessage).getRoutingKey();
        verify(outboundMessage).getBody();
    }
}