package org.reactivecommons.async.starter.config.disabled;

import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.From;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class DisabledDirectAsyncGatewayTest {

    private DisabledDirectAsyncGateway gateway;
    private static final String ERROR_MESSAGE = "sendCommands feature is disabled in ReactiveCommonsFeatures. " +
            "Set sendCommands=true to send commands or queries.";

    @Mock
    private CloudEvent cloudEvent;

    @Mock
    private Command<String> command;

    @Mock
    private AsyncQuery<String> query;

    @Mock
    private From from;

    @BeforeEach
    void setUp() {
        gateway = new DisabledDirectAsyncGateway();
    }

    @Test
    void shouldThrowErrorWhenSendingCommandWithoutDelay() {
        StepVerifier.create(gateway.sendCommand(command, "targetName"))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenSendingCommandWithDelay() {
        StepVerifier.create(gateway.sendCommand(command, "targetName", 1000L))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenSendingCommandWithDomain() {
        StepVerifier.create(gateway.sendCommand(command, "targetName", "domain"))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenSendingCommandWithDelayAndDomain() {
        StepVerifier.create(gateway.sendCommand(command, "targetName", 1000L, "domain"))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenSendingCloudEventCommand() {
        StepVerifier.create(gateway.sendCommand(cloudEvent, "targetName"))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenSendingCloudEventCommandWithDelay() {
        StepVerifier.create(gateway.sendCommand(cloudEvent, "targetName", 1000L))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenSendingCloudEventCommandWithDomain() {
        StepVerifier.create(gateway.sendCommand(cloudEvent, "targetName", "domain"))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenSendingCloudEventCommandWithDelayAndDomain() {
        StepVerifier.create(gateway.sendCommand(cloudEvent, "targetName", 1000L, "domain"))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenRequestingReplyWithQuery() {
        StepVerifier.create(gateway.requestReply(query, "targetName", String.class))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenRequestingReplyWithQueryAndDomain() {
        StepVerifier.create(gateway.requestReply(query, "targetName", String.class, "domain"))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenRequestingReplyWithCloudEvent() {
        StepVerifier.create(gateway.requestReply(cloudEvent, "targetName", CloudEvent.class))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenRequestingReplyWithCloudEventAndDomain() {
        StepVerifier.create(gateway.requestReply(cloudEvent, "targetName", CloudEvent.class, "domain"))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenReplyingToQuery() {
        StepVerifier.create(gateway.reply("response", from))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }
}
