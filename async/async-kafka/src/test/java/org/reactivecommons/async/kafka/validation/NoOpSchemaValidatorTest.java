package org.reactivecommons.async.kafka.validation;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;

class NoOpSchemaValidatorTest {

    private final SchemaValidator validator = NoOpSchemaValidator.INSTANCE;

    @Test
    void shouldAcceptAnyOutboundPayload() {
        assertThatCode(() -> validator.validateOutbound("topic", "{}".getBytes(StandardCharsets.UTF_8),
                new RecordHeaders())).doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptAnyInboundPayload() {
        assertThatCode(() -> validator.validateInbound("topic", "not even json".getBytes(StandardCharsets.UTF_8),
                new RecordHeaders())).doesNotThrowAnyException();
    }
}
