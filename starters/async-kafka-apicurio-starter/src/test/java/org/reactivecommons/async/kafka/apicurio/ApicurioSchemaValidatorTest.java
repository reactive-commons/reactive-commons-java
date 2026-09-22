package org.reactivecommons.async.kafka.apicurio;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.apicurio.registry.resolver.ParsedSchemaImpl;
import io.apicurio.registry.resolver.SchemaLookupResult;
import io.apicurio.registry.resolver.SchemaResolver;
import io.apicurio.registry.resolver.strategy.ArtifactReference;
import io.apicurio.registry.serde.kafka.headers.DefaultHeadersHandler;
import io.apicurio.registry.serde.kafka.headers.HeadersHandler;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.kafka.validation.SchemaValidationException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApicurioSchemaValidatorTest {

    private static final String SCHEMA = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "age": { "type": "integer" }
              },
              "required": ["name"]
            }
            """;

    private static final byte[] VALID = "{\"name\":\"john\",\"age\":30}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID = "{\"age\":\"thirty\"}".getBytes(StandardCharsets.UTF_8);

    @Mock
    private SchemaResolver<JsonSchema, Object> schemaResolver;

    private HeadersHandler headersHandler;
    private ApicurioSchemaValidator validator;

    @BeforeEach
    void setUp() {
        headersHandler = new DefaultHeadersHandler();
        headersHandler.configure(new HashMap<>(), false);
        validator = ApicurioSchemaValidator.builder()
                .schemaResolver(schemaResolver)
                .headersHandler(headersHandler)
                .artifactReferenceProvider(new DefaultArtifactReferenceProvider("default", null, null))
                .build();
    }

    @Test
    void shouldReportValidationFailuresInEnglishRegardlessOfTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("es-CO"));
            givenSchemaIsResolved();
            Headers headers = new RecordHeaders();

            assertThatThrownBy(() -> validator.validateInbound("person.topic", INVALID, headers))
                    .isInstanceOf(SchemaValidationException.class)
                    .hasMessageContaining("required property 'name' not found")
                    .hasMessageNotContaining("no se encontr");
        } finally {
            Locale.setDefault(original);
        }
    }

    private void givenSchemaIsResolved() {
        givenSchemaIsResolved(SCHEMA);
    }

    private void givenSchemaIsResolved(String rawSchema) {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(rawSchema);
        SchemaLookupResult<JsonSchema> result = SchemaLookupResult.<JsonSchema>builder()
                .parsedSchema(new ParsedSchemaImpl<JsonSchema>()
                        .setParsedSchema(schema)
                        .setRawSchema(rawSchema.getBytes(StandardCharsets.UTF_8)))
                .groupId("default")
                .artifactId("person")
                .version("1")
                .globalId(42L)
                .build();
        when(schemaResolver.resolveSchemaByArtifactReference(any())).thenReturn(result);
    }

    @Test
    void shouldAcceptValidOutboundPayloadAndWriteSchemaCoordinatesInHeaders() {
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();

        validator.validateOutbound("person.topic", VALID, headers);

        // The coordinates written must be readable back by an Apicurio consumer
        ArtifactReference written = headersHandler.readHeaders(headers);
        assertThat(written.getGroupId()).isEqualTo("default");
        assertThat(written.getArtifactId()).isEqualTo("person");
        assertThat(written.getVersion()).isEqualTo("1");
        assertThat(written.getContentId()).isNull();
    }

    @Test
    void shouldRejectInvalidOutboundPayload() {
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();

        assertThatThrownBy(() -> validator.validateOutbound("person.topic", INVALID, headers))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("person.topic");
    }

    @Test
    void shouldKeepTheVersionOfTheHeadersWhenTheyNameTheExpectedArtifact() {
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();
        headersHandler.writeHeaders(headers, ArtifactReference.builder()
                .groupId("default")
                .artifactId("person.topic-value")
                .version("3")
                .build());

        validator.validateInbound("person.topic", VALID, headers);

        verify(schemaResolver).resolveSchemaByArtifactReference(argThat(reference ->
                "person.topic-value".equals(reference.getArtifactId()) && "3".equals(reference.getVersion())));
    }

    @Test
    void shouldNotLetAnIncomingRecordChooseAnotherArtifact() {
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();
        headersHandler.writeHeaders(headers, ArtifactReference.builder()
                .groupId("default")
                .artifactId("anything-goes")
                .version("1")
                .build());

        validator.validateInbound("person.topic", VALID, headers);

        // The configured artifact wins, so publishing a record cannot pick the schema it is validated against
        verify(schemaResolver).resolveSchemaByArtifactReference(argThat(reference ->
                "person.topic-value".equals(reference.getArtifactId()) && reference.getVersion() == null));
    }

    @Test
    void shouldNotLetAnIncomingRecordChooseAnotherGroup() {
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();
        headersHandler.writeHeaders(headers, ArtifactReference.builder()
                .groupId("another-group")
                .artifactId("person.topic-value")
                .version("1")
                .build());

        validator.validateInbound("person.topic", VALID, headers);

        verify(schemaResolver).resolveSchemaByArtifactReference(argThat(reference ->
                "default".equals(reference.getGroupId()) && reference.getVersion() == null));
    }

    @Test
    void shouldIgnoreTheGlobalIdOfAnIncomingRecord() {
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();
        headersHandler.writeHeaders(headers, ArtifactReference.builder()
                .groupId("default")
                .artifactId("person.topic-value")
                .version("1")
                .build());
        headers.add("apicurio.value.globalId", ByteBuffer.allocate(8).putLong(999L).array());

        validator.validateInbound("person.topic", VALID, headers);

        // A globalId takes precedence over the coordinates when resolving, so it may not be forwarded
        verify(schemaResolver).resolveSchemaByArtifactReference(argThat(reference ->
                reference.getGlobalId() == null && "person.topic-value".equals(reference.getArtifactId())));
    }

    @Test
    void shouldTreatAMissingGroupIdAsTheDefaultGroup() {
        ApicurioSchemaValidator withoutGroup = ApicurioSchemaValidator.builder()
                .schemaResolver(schemaResolver)
                .headersHandler(headersHandler)
                .artifactReferenceProvider(new DefaultArtifactReferenceProvider(null, "person", null))
                .build();
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();
        headersHandler.writeHeaders(headers, ArtifactReference.builder()
                .groupId("default")
                .artifactId("person")
                .version("7")
                .build());

        withoutGroup.validateInbound("person.topic", VALID, headers);

        verify(schemaResolver).resolveSchemaByArtifactReference(argThat(reference ->
                "7".equals(reference.getVersion())));
    }

    @Test
    void shouldFallBackToTheConfiguredArtifactWhenTheHeadersAreMalformed() {
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();
        // A globalId header shorter than a long makes the Apicurio handler fail while reading its buffer
        headers.add("apicurio.value.globalId", new byte[]{1, 2, 3});

        assertThatCode(() -> validator.validateInbound("person.topic", VALID, headers))
                .doesNotThrowAnyException();
        verify(schemaResolver).resolveSchemaByArtifactReference(argThatArtifactIs("person.topic-value"));
    }

    @Test
    void shouldFallBackToTopicConventionWhenHeadersHaveNoCoordinates() {
        givenSchemaIsResolved();

        validator.validateInbound("person.topic", VALID, new RecordHeaders());

        verify(schemaResolver).resolveSchemaByArtifactReference(argThatArtifactIs("person.topic-value"));
    }

    @Test
    void shouldRejectInvalidInboundPayload() {
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();

        assertThatThrownBy(() -> validator.validateInbound("person.topic", INVALID, headers))
                .isInstanceOf(SchemaValidationException.class);
    }

    @Test
    void shouldRejectPayloadThatIsNotJson() {
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();
        byte[] notJson = "<not-json>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validateInbound("person.topic", notJson, headers))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("as JSON");
    }

    @Test
    void shouldWrapRegistryFailures() {
        when(schemaResolver.resolveSchemaByArtifactReference(any()))
                .thenThrow(new IllegalStateException("registry is down"));
        Headers headers = new RecordHeaders();

        assertThatThrownBy(() -> validator.validateInbound("person.topic", VALID, headers))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("Unable to resolve the schema");
    }

    @Test
    void shouldRejectARegistryThatResolvesNothing() {
        when(schemaResolver.resolveSchemaByArtifactReference(any())).thenReturn(null);
        Headers headers = new RecordHeaders();

        assertThatThrownBy(() -> validator.validateInbound("person.topic", VALID, headers))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("No schema was resolved");
    }

    @Test
    void shouldValidateOutboundAndInboundOfTheSameValidator() {
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();

        assertThatCode(() -> validator.validateOutbound("person.topic", VALID, headers))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateInbound("person.topic", VALID, headers))
                .doesNotThrowAnyException();
        verify(schemaResolver, times(2)).resolveSchemaByArtifactReference(any());
    }

    private static ArtifactReference argThatArtifactIs(String artifactId) {
        return org.mockito.ArgumentMatchers.argThat(reference ->
                reference != null && artifactId.equals(reference.getArtifactId()));
    }

    private static int countOccurrences(String text, String fragment) {
        int count = 0;
        int index = text.indexOf(fragment);
        while (index >= 0) {
            count++;
            index = text.indexOf(fragment, index + fragment.length());
        }
        return count;
    }

    @Test
    void shouldNotFailWhenThereAreNoHeadersToWriteInto() {
        givenSchemaIsResolved();

        assertThatCode(() -> validator.validateOutbound("person.topic", VALID, null))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldKeepTheVersionOfARecordItPublishedItself() {
        // A round trip through the very same validator: the coordinates written when publishing must still
        // select the version they name when the record is consumed back
        ApicurioSchemaValidator roundTrip = ApicurioSchemaValidator.builder()
                .schemaResolver(schemaResolver)
                .headersHandler(headersHandler)
                .artifactReferenceProvider(new DefaultArtifactReferenceProvider("default", "person", null))
                .build();
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();

        roundTrip.validateOutbound("person.topic", VALID, headers);
        roundTrip.validateInbound("person.topic", VALID, headers);

        verify(schemaResolver).resolveSchemaByArtifactReference(argThat(reference ->
                "person".equals(reference.getArtifactId()) && "1".equals(reference.getVersion())));
    }

    @Test
    void shouldNotLetARecordOverrideTheConfiguredVersion() {
        // The topic pins version 1, the record says it was published with version 2
        ApicurioSchemaValidator pinned = ApicurioSchemaValidator.builder()
                .schemaResolver(schemaResolver)
                .headersHandler(headersHandler)
                .artifactReferenceProvider(new DefaultArtifactReferenceProvider("kafka", "person", "1"))
                .build();
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();
        headersHandler.writeHeaders(headers, ArtifactReference.builder()
                .groupId("kafka")
                .artifactId("person")
                .version("2")
                .build());

        pinned.validateInbound("person.topic", VALID, headers);

        verify(schemaResolver).resolveSchemaByArtifactReference(argThat(reference ->
                "person".equals(reference.getArtifactId()) && "1".equals(reference.getVersion())));
    }

    @Test
    void shouldExplainThatTheVersionIsPinnedWhenARecordIsRejected() {
        ApicurioSchemaValidator pinned = ApicurioSchemaValidator.builder()
                .schemaResolver(schemaResolver)
                .headersHandler(headersHandler)
                .artifactReferenceProvider(new DefaultArtifactReferenceProvider("kafka", "person", "1"))
                .build();
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();
        headersHandler.writeHeaders(headers, ArtifactReference.builder()
                .groupId("kafka")
                .artifactId("person")
                .version("2")
                .build());

        assertThatThrownBy(() -> pinned.validateInbound("person.topic", INVALID, headers))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("The record carried the schema coordinates")
                .hasMessageContaining("artifactId=person, version=2")
                .hasMessageContaining("artifactId=person, version=1")
                // A wrapping exception would repeat the whole failure again under "Caused by"
                .hasNoCause();
    }

    @Test
    void shouldNotRepeatTheFailureInTheCauseChain() {
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();
        headers.add("apicurio.value.contentId", ByteBuffer.allocate(8).putLong(7L).array());

        Throwable failure = catchThrowable(() -> validator.validateInbound("person.topic", INVALID, headers));

        assertThat(failure).isInstanceOf(SchemaValidationException.class).hasNoCause();
        assertThat(countOccurrences(failure.getMessage(), "required property 'name' not found")).isOne();
    }

    @Test
    void shouldNotReportAnythingWhenTheHeadersNameTheSameArtifactWithoutVersion() {
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();
        headersHandler.writeHeaders(headers, ArtifactReference.builder()
                .groupId("default")
                .artifactId("person.topic-value")
                .build());

        // The coordinates are the ones of the topic, so claiming they were discarded would be false
        assertThatThrownBy(() -> validator.validateInbound("person.topic", INVALID, headers))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageNotContaining("The record carried the schema coordinates");
    }

    @Test
    void shouldExplainTheDiscardedCoordinatesWhenARecordIsRejected() {
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();
        // What an Apicurio serde producer writes by default: a content id, which cannot be matched by name
        headers.add("apicurio.value.contentId", ByteBuffer.allocate(8).putLong(7L).array());

        assertThatThrownBy(() -> validator.validateInbound("person.topic", INVALID, headers))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("required property 'name' not found")
                .hasMessageContaining("The record carried the schema coordinates");
    }

    @Test
    void shouldNotMentionAnythingWhenThereWereNoCoordinatesToDiscard() {
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();

        assertThatThrownBy(() -> validator.validateInbound("person.topic", INVALID, headers))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageNotContaining("The record carried the schema coordinates");
    }

    @Test
    void shouldNotMentionAnythingWhenTheCoordinatesWereHonoured() {
        givenSchemaIsResolved();
        Headers headers = new RecordHeaders();
        headersHandler.writeHeaders(headers, ArtifactReference.builder()
                .groupId("default")
                .artifactId("person.topic-value")
                .version("2")
                .build());

        assertThatThrownBy(() -> validator.validateInbound("person.topic", INVALID, headers))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageNotContaining("The record carried the schema coordinates");
    }

    @Test
    void shouldReleaseTheResolverWhenClosed() throws Exception {
        validator.close();

        verify(schemaResolver).close();
    }

    @Test
    void shouldNotReleaseAResolverItDoesNotOwn() throws Exception {
        ApicurioSchemaValidator sharing = ApicurioSchemaValidator.builder()
                .schemaResolver(schemaResolver)
                .ownsResolver(false)
                .headersHandler(headersHandler)
                .artifactReferenceProvider(new DefaultArtifactReferenceProvider(null, null, null))
                .build();

        sharing.close();

        // A shared resolver serves several domains, only its creator may release it
        verify(schemaResolver, never()).close();
    }

    @Test
    void shouldReportOnlyTheFirstFailuresOfAnInvalidPayload() {
        givenSchemaIsResolved("""
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "array",
                  "items": { "type": "integer" }
                }
                """);
        StringJoiner items = new StringJoiner(",", "[", "]");
        for (int i = 0; i < 40; i++) {
            items.add("\"not-an-integer\"");
        }
        Headers headers = new RecordHeaders();
        byte[] payload = items.toString().getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validateInbound("person.topic", payload, headers))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("and 30 more");
    }
}
