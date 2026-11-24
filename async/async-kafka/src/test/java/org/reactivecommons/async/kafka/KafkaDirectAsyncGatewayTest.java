package org.reactivecommons.async.kafka;

import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.From;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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

    /**
     * KC-27: every operation fails through {@code onError}, so the error handlers of the caller see it; calling it
     * does not throw.
     */
    @Test
    void allMethodsAreNotImplemented() {
        assertFailsThroughOnError(() -> directAsyncGateway.sendCommand(cloudEvent, targetName));
        assertFailsThroughOnError(() -> directAsyncGateway.sendCommand(cloudEvent, targetName, domain));
        assertFailsThroughOnError(() -> directAsyncGateway.sendCommand(cloudEvent, targetName, delay));
        assertFailsThroughOnError(() -> directAsyncGateway.sendCommand(cloudEvent, targetName, delay, domain));
        assertFailsThroughOnError(() -> directAsyncGateway.sendCommand(command, targetName));
        assertFailsThroughOnError(() -> directAsyncGateway.sendCommand(command, targetName, domain));
        assertFailsThroughOnError(() -> directAsyncGateway.sendCommand(command, targetName, delay));
        assertFailsThroughOnError(() -> directAsyncGateway.sendCommand(command, targetName, delay, domain));
        assertFailsThroughOnError(() -> directAsyncGateway.requestReply(cloudEvent, targetName, CloudEvent.class));
        assertFailsThroughOnError(
                () -> directAsyncGateway.requestReply(cloudEvent, targetName, CloudEvent.class, domain));
        assertFailsThroughOnError(() -> directAsyncGateway.requestReply(query, targetName, CloudEvent.class));
        assertFailsThroughOnError(() -> directAsyncGateway.requestReply(query, targetName, CloudEvent.class, domain));
        assertFailsThroughOnError(() -> directAsyncGateway.reply(targetName, from));
    }

    private static void assertFailsThroughOnError(ThrowingSupplier<Mono<?>> operation) {
        StepVerifier.create(assertDoesNotThrow(operation))
                .expectError(UnsupportedOperationException.class)
                .verify();
    }
}
