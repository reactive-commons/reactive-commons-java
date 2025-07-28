package org.reactivecommons.async.rabbit.communications;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnroutableMessageProcessorTest {

    @Mock
    private OutboundMessageResult<MyOutboundMessage> messageResult;

    @Mock
    private MyOutboundMessage myOutboundMessage;

    @InjectMocks
    private UnroutableMessageProcessor unroutableMessageProcessor;


    @Test
    void logsUnroutableMessageDetails() {
        when(messageResult.getOutboundMessage()).thenReturn(myOutboundMessage);
        when(myOutboundMessage.getExchange()).thenReturn("test-exchange");
        when(myOutboundMessage.getRoutingKey()).thenReturn("test-routingKey");
        when(myOutboundMessage.getBody()).thenReturn("test-body".getBytes(StandardCharsets.UTF_8));
        when(myOutboundMessage.getProperties()).thenReturn(null);

        StepVerifier.create(unroutableMessageProcessor.processMessage(messageResult))
                .verifyComplete();

        verify(messageResult).getOutboundMessage();
        verify(myOutboundMessage).getExchange();
        verify(myOutboundMessage).getRoutingKey();
        verify(myOutboundMessage).getBody();
    }
}