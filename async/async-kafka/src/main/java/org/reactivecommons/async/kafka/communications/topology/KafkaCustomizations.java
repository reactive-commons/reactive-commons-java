package org.reactivecommons.async.kafka.communications.topology;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class KafkaCustomizations {
    private Map<String, TopicCustomization> topics = new HashMap<>();

    public static KafkaCustomizations withTopic(String topic, TopicCustomization customization) {
        KafkaCustomizations customizations = new KafkaCustomizations();
        customizations.getTopics().put(topic, customization);
        return customizations;
    }

    public KafkaCustomizations addTopic(String topic, TopicCustomization customization) {
        this.getTopics().put(topic, customization);
        return this;
    }
}
