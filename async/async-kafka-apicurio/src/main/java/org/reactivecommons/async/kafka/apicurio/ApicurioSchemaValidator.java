package org.reactivecommons.async.kafka.apicurio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import io.apicurio.registry.resolver.ParsedSchema;
import io.apicurio.registry.resolver.SchemaLookupResult;
import io.apicurio.registry.resolver.SchemaResolver;
import io.apicurio.registry.resolver.strategy.ArtifactReference;
import io.apicurio.registry.resolver.strategy.ArtifactReferenceImpl;
import io.apicurio.registry.serde.kafka.headers.HeadersHandler;
import lombok.Builder;
import lombok.extern.java.Log;
import org.apache.kafka.common.header.Headers;
import org.reactivecommons.async.kafka.validation.SchemaValidationException;
import org.reactivecommons.async.kafka.validation.SchemaValidator;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.logging.Level;

/**
 * {@link SchemaValidator} backed by an Apicurio Registry.
 * <p>
 * Reactive Commons keeps the Kafka wire format as raw bytes, so this validator does not replace the
 * Kafka serdes: it resolves the JSON Schema registered in Apicurio and validates the payload that
 * Reactive Commons is about to publish, or has just consumed.
 * <p>
 * The schema coordinates always travel in the record headers, exactly as Apicurio's own serdes do when
 * {@code apicurio.registry.headers.enabled} is {@code true}. This is not optional: they are the only channel
 * Reactive Commons has to tell the consumer which schema version was used, so that already published records
 * keep being validated against their own version after the schema evolves.
 * <p>
 * <b>Resolving a schema is a blocking call</b> against the registry, cached by the underlying resolver, so this
 * validator must not be invoked from a thread that cannot block.
 */
@Log
public class ApicurioSchemaValidator implements SchemaValidator, Closeable {

    /**
     * Keeps validator messages in English.
     */
    private static final Locale MESSAGES_LOCALE = Locale.ENGLISH;

    /**
     * A single invalid record may produce one failure per element of a large array, so only the first ones are
     * reported and a wrong payload cannot turn every rejection into a huge message.
     */
    private static final int MAX_REPORTED_FAILURES = 10;

    /**
     * Apicurio resolves a missing group id as this group, so both spellings name the same one.
     */
    private static final String DEFAULT_GROUP_ID = "default";

    private final SchemaResolver<JsonSchema, Object> schemaResolver;
    private final boolean ownsResolver;
    private final HeadersHandler headersHandler;
    private final ArtifactReferenceProvider artifactReferenceProvider;
    private final ObjectMapper objectMapper;

    @Builder
    public ApicurioSchemaValidator(SchemaResolver<JsonSchema, Object> schemaResolver,
                                   Boolean ownsResolver,
                                   HeadersHandler headersHandler,
                                   ArtifactReferenceProvider artifactReferenceProvider,
                                   ObjectMapper objectMapper) {
        this.schemaResolver = schemaResolver;
        this.ownsResolver = ownsResolver == null || ownsResolver;
        this.headersHandler = headersHandler;
        this.artifactReferenceProvider = artifactReferenceProvider;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public void validateOutbound(String topic, byte[] payload, Headers headers) {
        ArtifactReference reference = artifactReferenceProvider.referenceFor(topic);
        SchemaLookupResult<JsonSchema> lookup = resolve(topic, reference);
        validate(topic, payload, lookup.getParsedSchema(), null);
        if (headers != null) {
            headersHandler.writeHeaders(headers, resolvedCoordinates(lookup));
        }
    }

    /**
     * Builds the reference written in the headers from the coordinates actually resolved.
     * <p>
     * {@code SchemaLookupResult#toArtifactReference()} is not used because its {@code contentId} and
     * {@code globalId} are primitives, so an unset value is written as {@code 0} and the consumer would try to
     * resolve a non-existing artifact. Only the ids really present are propagated.
     */
    private ArtifactReference resolvedCoordinates(SchemaLookupResult<JsonSchema> lookup) {
        ArtifactReferenceImpl.ArtifactReferenceBuilder builder = ArtifactReference.builder()
                .groupId(lookup.getGroupId())
                .artifactId(lookup.getArtifactId())
                .version(lookup.getVersion());
        if (lookup.getGlobalId() != 0L) {
            builder.globalId(lookup.getGlobalId());
        }
        return builder.build();
    }

    @Override
    public void validateInbound(String topic, byte[] payload, Headers headers) {
        ArtifactReference expected = artifactReferenceProvider.referenceFor(topic);
        InboundArtifact artifact = inboundArtifact(expected, headers);
        validate(topic, payload, resolve(topic, artifact.reference()).getParsedSchema(), artifact.hint());
    }

    /**
     * The artifact an incoming record is validated against, along with the coordinates that were discarded to
     * choose it, if any.
     */
    private record InboundArtifact(ArtifactReference reference, ArtifactReference discarded) {

        static InboundArtifact of(ArtifactReference reference) {
            return new InboundArtifact(reference, null);
        }

        /**
         * Explains, only when a record is rejected, that it was not checked against the schema it names.
         *
         * @return the explanation to append to the rejection, or {@code null} when nothing was discarded
         */
        String hint() {
            if (discarded == null) {
                return null;
            }
            return String.format("The record carried the schema coordinates %s but it was validated against %s",
                    discarded, reference);
        }
    }

    /**
     * Decides which artifact an incoming record is validated against.
     * <p>
     * A version configured explicitly wins over the one of the record: pinning it is how a topic declares the
     * single contract it accepts, so a producer cannot move the consumer onto another version by publishing it in
     * the headers. Leave the version empty for the records to be validated against their own version.
     */
    private InboundArtifact inboundArtifact(ArtifactReference expected, Headers headers) {
        ArtifactReference fromHeaders = readHeaders(headers);
        if (fromHeaders == null) {
            return InboundArtifact.of(expected);
        }
        // Either the topic pins its own version, or the headers name a different artifact altogether: in both
        // cases nothing from the headers may be taken, and the coordinates are kept only to explain a rejection.
        if (isSet(expected.getVersion()) || !identifiesSameArtifact(expected, fromHeaders)) {
            return discard(expected, fromHeaders);
        }
        String version = fromHeaders.getVersion();
        if (!isSet(version)) {
            // They name the very artifact of the topic and carry no version, so there is nothing to take from
            // them and nothing to report either
            return InboundArtifact.of(expected);
        }
        return InboundArtifact.of(ArtifactReference.builder()
                .groupId(expected.getGroupId())
                .artifactId(expected.getArtifactId())
                .version(version)
                .build());
    }

    private static InboundArtifact discard(ArtifactReference expected, ArtifactReference fromHeaders) {
        log.log(Level.FINE, "Ignoring the schema coordinates {0} of an incoming record, the topic is validated "
                + "against {1}", new Object[]{fromHeaders, expected});
        return new InboundArtifact(expected, fromHeaders);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Points at the discarded coordinates when a record is rejected.
     * <p>
     * A record validated against an artifact other than the one it names fails in ways that are hard to read: the
     * setup looks healthy until the schema evolves or until a producer moves to another version.
     */
    private static boolean identifiesSameArtifact(ArtifactReference expected, ArtifactReference fromHeaders) {
        return expected.getArtifactId() != null
                && expected.getArtifactId().equals(fromHeaders.getArtifactId())
                && Objects.equals(groupOf(expected), groupOf(fromHeaders));
    }

    private static String groupOf(ArtifactReference reference) {
        String groupId = reference.getGroupId();
        return groupId == null || groupId.isBlank() ? DEFAULT_GROUP_ID : groupId;
    }

    private ArtifactReference readHeaders(Headers headers) {
        if (headers == null) {
            return null;
        }
        ArtifactReference reference;
        try {
            reference = headersHandler.readHeaders(headers);
        } catch (RuntimeException e) {
            // A truncated globalId or contentId header makes the handler fail while reading its buffer. The
            // record is still validated, against the artifact configured for the topic.
            log.log(Level.FINE, "Unreadable schema coordinates in an incoming record", e);
            return null;
        }
        boolean empty = reference == null || (!reference.hasValue() && reference.getContentHash() == null);
        return empty ? null : reference;
    }

    private SchemaLookupResult<JsonSchema> resolve(String topic, ArtifactReference reference) {
        SchemaLookupResult<JsonSchema> lookup;
        try {
            lookup = schemaResolver.resolveSchemaByArtifactReference(reference);
        } catch (Exception e) {
            throw new SchemaValidationException(
                    String.format("Unable to resolve the schema for topic %s and reference %s", topic, reference), e);
        }
        if (lookup == null) {
            throw new SchemaValidationException(
                    String.format("No schema was resolved for topic %s and reference %s", topic, reference));
        }
        return lookup;
    }

    private void validate(String topic, byte[] payload, ParsedSchema<JsonSchema> schema, String hint) {
        if (schema == null || schema.getParsedSchema() == null) {
            throw new SchemaValidationException("No schema was resolved for topic " + topic);
        }
        Set<ValidationMessage> failures;
        try {
            failures = schema.getParsedSchema().validate(objectMapper.readTree(payload),
                    executionContext -> executionContext.getExecutionConfig().setLocale(MESSAGES_LOCALE));
        } catch (Exception e) {
            throw new SchemaValidationException("Unable to read the payload of topic " + topic + " as JSON", e);
        }
        if (failures != null && !failures.isEmpty()) {
            String message = String.format("Error validating data of topic %s against json schema: %s",
                    topic, describe(failures));
            throw new SchemaValidationException(hint == null ? message : message + ". " + hint);
        }
    }

    private static String describe(Set<ValidationMessage> failures) {
        StringJoiner joiner = new StringJoiner(", ");
        Iterator<ValidationMessage> iterator = failures.iterator();
        int reported = 0;
        while (iterator.hasNext() && reported < MAX_REPORTED_FAILURES) {
            joiner.add(iterator.next().getMessage());
            reported++;
        }
        int remaining = failures.size() - reported;
        if (remaining > 0) {
            joiner.add("and " + remaining + " more");
        }
        return joiner.toString();
    }

    /**
     * Releases the registry client backing the resolver, unless the resolver was handed over by the caller. A
     * shared resolver serves several domains, so only whoever created it may release it.
     */
    @Override
    public void close() throws IOException {
        if (ownsResolver) {
            schemaResolver.close();
        }
    }
}
