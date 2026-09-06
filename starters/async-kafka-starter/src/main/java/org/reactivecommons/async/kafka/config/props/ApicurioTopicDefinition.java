package org.reactivecommons.async.kafka.config.props;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * A single Kafka topic validated against a registry, and the settings it overrides from that registry.
 * <p>
 * A topic with no {@code properties} inherits every setting of its registry, which in turn inherits the ones of its
 * domain. The properties declared here are merged over those, key by key, so a topic usually only names what makes
 * it different, most often {@code apicurio.registry.artifact.artifact-id} or
 * {@code apicurio.registry.artifact.group-id}.
 * <p>
 * Only the topics listed under a registry are validated when the domain declares registries. A topic that is
 * produced or consumed but is not listed is left alone, which is how validation is turned off for one topic without
 * touching the rest.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ApicurioTopicDefinition {

    /**
     * Name of the Kafka topic, exactly as it travels in the record.
     */
    private String name;

    /**
     * Apicurio settings that win over the ones of the registry, using their original Apicurio keys.
     * <p>
     * {@code apicurio.registry.serde.validation-enabled: false} leaves this topic unvalidated, which is also what
     * happens when the topic is not declared at all.
     */
    @Builder.Default
    private Map<String, String> properties = new HashMap<>();
}
