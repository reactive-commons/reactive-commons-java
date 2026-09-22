package org.reactivecommons.async.kafka.apicurio;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TopicSchemaValidatorRouterTest {

    private static final byte[] PAYLOAD = "{}".getBytes(StandardCharsets.UTF_8);

    private final SchemaValidator events = mock(SchemaValidator.class);
    private final SchemaValidator audit = mock(SchemaValidator.class);
    private final TopicSchemaValidatorRouter router =
            new TopicSchemaValidatorRouter(Map.of("eventos-topic", events, "auditoria-topic", audit));

    @Test
    void shouldApplyTheValidatorOfTheTopicOfTheRecord() {
        var headers = new RecordHeaders();

        router.validateOutbound("eventos-topic", PAYLOAD, headers);
        router.validateInbound("auditoria-topic", PAYLOAD, headers);

        verify(events).validateOutbound("eventos-topic", PAYLOAD, headers);
        verify(audit).validateInbound("auditoria-topic", PAYLOAD, headers);
        verifyNoInteractions(mock(SchemaValidator.class));
    }

    @Test
    void shouldNotValidateATopicThatIsNotDeclared() {
        var headers = new RecordHeaders();

        router.validateOutbound("push", PAYLOAD, headers);
        router.validateInbound("push", PAYLOAD, headers);

        assertThat(router.forTopic("push")).isSameAs(NoOpSchemaValidator.INSTANCE);
        verifyNoInteractions(events, audit);
    }

    @Test
    void shouldExposeTheValidatedTopics() {
        assertThat(router.validatedTopics()).containsOnlyKeys("eventos-topic", "auditoria-topic");
    }
}
