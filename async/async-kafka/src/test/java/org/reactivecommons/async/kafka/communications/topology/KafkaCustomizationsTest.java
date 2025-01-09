package org.reactivecommons.async.kafka.communications.topology;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class KafkaCustomizationsTest {

    private KafkaCustomizations customizations;

    @BeforeEach
    void setUp() {
        customizations = new KafkaCustomizations();
    }

    @Test
    void testWithTopic() {
        String topic = "testTopic";
        Map<String, String> config = new HashMap<>();
        config.put("cleanup.policy", "compact");
        TopicCustomization customization = new TopicCustomization(topic, 3, (short) 1, config);
        KafkaCustomizations result = KafkaCustomizations.withTopic(topic, customization);

        assertNotNull(result);
        assertEquals(1, result.getTopics().size());
        assertEquals(customization, result.getTopics().get(topic));
    }

    @Test
    void testAddTopic() {
        String topic1 = "testTopic1";
        Map<String, String> config1 = new HashMap<>();
        config1.put("cleanup.policy", "compact");
        TopicCustomization customization1 = new TopicCustomization(topic1, 3, (short) 1, config1);
        customizations.addTopic(topic1, customization1);

        String topic2 = "testTopic2";
        Map<String, String> config2 = new HashMap<>();
        config2.put("retention.ms", "60000");
        TopicCustomization customization2 = new TopicCustomization(topic2, 5, (short) 2, config2);
        customizations.addTopic(topic2, customization2);

        assertEquals(2, customizations.getTopics().size());
        assertEquals(customization1, customizations.getTopics().get(topic1));
        assertEquals(customization2, customizations.getTopics().get(topic2));
    }
}