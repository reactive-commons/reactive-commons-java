package org.reactivecommons.async.kafka.communications.topology;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TopicCustomization {
    private String topic;
    private int partitions;
    private short replicationFactor;
    private Map<String, String> config;
}
