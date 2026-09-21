package org.reactivecommons.async.kafka.apicurio;

import io.apicurio.registry.resolver.strategy.ArtifactReference;

/**
 * Resolves which Apicurio artifact (schema) must be used to validate the payload of a topic.
 * <p>
 * It is only consulted when the record does not carry the schema coordinates in its headers,
 * which is always the case on the producer side.
 */
@FunctionalInterface
public interface ArtifactReferenceProvider {

    /**
     * @param topic the topic being produced to or consumed from
     * @return the coordinates of the artifact holding the schema, never {@code null}
     */
    ArtifactReference referenceFor(String topic);
}
