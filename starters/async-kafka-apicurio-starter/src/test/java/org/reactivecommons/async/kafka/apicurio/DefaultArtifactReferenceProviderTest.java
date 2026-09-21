package org.reactivecommons.async.kafka.apicurio;

import io.apicurio.registry.resolver.strategy.ArtifactReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultArtifactReferenceProviderTest {

    @Test
    void shouldUseTopicConventionWhenNoExplicitArtifactIsConfigured() {
        ArtifactReference reference = new DefaultArtifactReferenceProvider(null, null, null)
                .referenceFor("events.topic");

        assertThat(reference.getArtifactId()).isEqualTo("events.topic-value");
        // Apicurio 3.x falls back to the implicit "default" group when none is given
        assertThat(reference.getGroupId()).isEqualTo("default");
        assertThat(reference.getVersion()).isNull();
    }

    @Test
    void shouldUseExplicitCoordinatesWhenConfigured() {
        ArtifactReference reference = new DefaultArtifactReferenceProvider("default", "person", "2")
                .referenceFor("events.topic");

        assertThat(reference.getGroupId()).isEqualTo("default");
        assertThat(reference.getArtifactId()).isEqualTo("person");
        assertThat(reference.getVersion()).isEqualTo("2");
    }

    @Test
    void shouldIgnoreBlankValues() {
        ArtifactReference reference = new DefaultArtifactReferenceProvider("  ", "  ", "  ")
                .referenceFor("events.topic");

        assertThat(reference.getGroupId()).isEqualTo("default");
        assertThat(reference.getArtifactId()).isEqualTo("events.topic-value");
        assertThat(reference.getVersion()).isNull();
    }
}
