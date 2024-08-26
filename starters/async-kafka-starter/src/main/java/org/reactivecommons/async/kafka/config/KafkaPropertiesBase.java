package org.reactivecommons.async.kafka.config;


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;

/**
 * This class is intended for set all the properties that Kafka needs to be configured.
 */
@Getter
@Setter
@NoArgsConstructor
public class KafkaPropertiesBase extends HashMap<String, Object> {
    private HashMap<String, Object> consumer = new HashMap<>();
    private HashMap<String, Object> producer = new HashMap<>();
}