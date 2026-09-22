package org.reactivecommons.async.kafka.apicurio;

/**
 * Convention used by {@link DefaultArtifactReferenceProvider} to derive the artifact id from the topic name, when
 * no fixed {@code apicurio.registry.artifact.artifact-id} is configured.
 */
enum ArtifactIdStrategy {

    /**
     * Mirrors Apicurio's {@code TopicIdStrategy}: {@code <topic>-value}. This is the default when neither an
     * explicit artifact id nor a strategy is configured.
     */
    TOPIC_ID {
        @Override
        String artifactIdFor(String topic) {
            return topic + VALUE_SUFFIX;
        }
    },

    /**
     * Mirrors Apicurio's {@code SimpleTopicIdStrategy}: the topic name itself, with no suffix.
     */
    SIMPLE_TOPIC_ID {
        @Override
        String artifactIdFor(String topic) {
            return topic;
        }
    };

    private static final String VALUE_SUFFIX = "-value";

    abstract String artifactIdFor(String topic);
}
