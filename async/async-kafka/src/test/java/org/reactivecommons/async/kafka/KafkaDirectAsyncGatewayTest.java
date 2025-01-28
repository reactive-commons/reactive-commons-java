package org.reactivecommons.async.kafka;

import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.From;

import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class KafkaDirectAsyncGatewayTest {
    private final DirectAsyncGateway directAsyncGateway = new KafkaDirectAsyncGateway();
    private final String targetName = "targetName";
    private final String domain = "domain";
    private final long delay = 1000L;
    @Mock
    private CloudEvent cloudEvent;
    @Mock
    private Command<String> command;
    @Mock
    private AsyncQuery<String> query;
    @Mock
    private From from;

    @Test
    void allMethodsAreNotImplemented() {
        assertThrows(UnsupportedOperationException.class,
                () -> directAsyncGateway.sendCommand(cloudEvent, targetName)
        );
        assertThrows(UnsupportedOperationException.class,
                () -> directAsyncGateway.sendCommand(cloudEvent, targetName, domain)
        );
        assertThrows(UnsupportedOperationException.class,
                () -> directAsyncGateway.sendCommand(cloudEvent, targetName, delay)
        );
        assertThrows(UnsupportedOperationException.class,
                () -> directAsyncGateway.sendCommand(cloudEvent, targetName, delay, domain)
        );
        assertThrows(UnsupportedOperationException.class,
                () -> directAsyncGateway.sendCommand(command, targetName)
        );
        assertThrows(UnsupportedOperationException.class,
                () -> directAsyncGateway.sendCommand(command, targetName, domain)
        );
        assertThrows(UnsupportedOperationException.class,
                () -> directAsyncGateway.sendCommand(command, targetName, delay)
        );
        assertThrows(UnsupportedOperationException.class,
                () -> directAsyncGateway.sendCommand(command, targetName, delay, domain)
        );
        assertThrows(UnsupportedOperationException.class,
                () -> directAsyncGateway.requestReply(cloudEvent, targetName, CloudEvent.class)
        );
        assertThrows(UnsupportedOperationException.class,
                () -> directAsyncGateway.requestReply(cloudEvent, targetName, CloudEvent.class, domain)
        );
        assertThrows(UnsupportedOperationException.class,
                () -> directAsyncGateway.requestReply(query, targetName, CloudEvent.class)
        );
        assertThrows(UnsupportedOperationException.class,
                () -> directAsyncGateway.requestReply(query, targetName, CloudEvent.class, domain)
        );
        assertThrows(UnsupportedOperationException.class, () -> directAsyncGateway.reply(targetName, from));
    }
}
