package org.reactivecommons.async.kafka.apicurio;

import io.apicurio.registry.resolver.strategy.ArtifactReference;
import lombok.RequiredArgsConstructor;

/**
 * Default {@link ArtifactReferenceProvider}.
 * <p>
 * When explicit coordinates are configured ({@code apicurio.registry.artifact.artifact-id}) they win
 * for every topic. Otherwise, the artifact id is derived from the topic name following {@code idStrategy}.
 */
@RequiredArgsConstructor
public class DefaultArtifactReferenceProvider implements ArtifactReferenceProvider {

    private final String explicitGroupId;
    private final String explicitArtifactId;
    private final String explicitVersion;
    private final ArtifactIdStrategy idStrategy;

    /**
     * Keeps the {@code TopicIdStrategy} convention when no strategy is configured, as it did before
     * {@link ArtifactIdStrategy} existed.
     */
    public DefaultArtifactReferenceProvider(String explicitGroupId, String explicitArtifactId,
                                            String explicitVersion) {
        this(explicitGroupId, explicitArtifactId, explicitVersion, ArtifactIdStrategy.TOPIC_ID);
    }

    @Override
    public ArtifactReference referenceFor(String topic) {
        String artifactId = (explicitArtifactId != null && !explicitArtifactId.isBlank())
                ? explicitArtifactId
                : idStrategy.artifactIdFor(topic);

        return ArtifactReference.builder()
                .groupId(emptyToNull(explicitGroupId))
                .artifactId(artifactId)
                .version(emptyToNull(explicitVersion))
                .build();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
