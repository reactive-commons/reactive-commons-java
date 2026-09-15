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
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ApicurioTopic {

    /**
     * Name of the Kafka topic, exactly as it travels in the record.
     */
    private String name;

    /**
     * Apicurio settings that win over the ones of the registry, using their original Apicurio keys.
     */
    @Builder.Default
    private Map<String, String> properties = new HashMap<>();
}
