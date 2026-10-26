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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
        Logger logger = Logger.getLogger(UnroutableMessageProcessor.class.getName());
        List<String> logged = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                logged.add(logRecord.getMessage());
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
        try {
            unroutableMessageProcessor.processMessage(messageResult).block();
        } finally {
            logger.removeHandler(capture);
        }

        assertThat(logged).singleElement().satisfies(line -> assertThat(line)
                .contains("a".repeat(1_024))
                .contains("truncated, 2011 bytes")
                .doesNotContain("END-OF-BODY"));
    }
}