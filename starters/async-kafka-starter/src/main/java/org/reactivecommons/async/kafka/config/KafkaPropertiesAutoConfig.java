package org.reactivecommons.async.kafka.config;


import org.reactivecommons.async.kafka.config.spring.KafkaPropertiesBase;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.kafka")
public class KafkaPropertiesAutoConfig extends KafkaPropertiesBase {
    public KafkaPropertiesAutoConfig() {
//        put("bootstrap.servers", "localhost:9092");
    }
}