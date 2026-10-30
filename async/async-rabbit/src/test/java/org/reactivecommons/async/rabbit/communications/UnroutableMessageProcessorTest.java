package org.reactivecommons.async.rabbit.communications;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
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

    /**
     * Unroutable messages are logged with the beginning of their body only: a body can weigh up to 16 MiB.
     */
    @Test
    void logsOnlyTheBeginningOfTheBody() {
        when(messageResult.outboundMessage()).thenReturn(outboundMessage);
        when(outboundMessage.getBody())
                .thenReturn(("a".repeat(2_000) + "END-OF-BODY").getBytes(StandardCharsets.UTF_8));
        Logger logger = (Logger) LoggerFactory.getLogger(UnroutableMessageProcessor.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        try {
            unroutableMessageProcessor.processMessage(messageResult).block();
        } finally {
            logger.detachAppender(logged);
        }

        assertThat(logged.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .contains("a".repeat(1_024))
                .contains("truncated, 2011 bytes")
                .doesNotContain("END-OF-BODY"));
    }
}