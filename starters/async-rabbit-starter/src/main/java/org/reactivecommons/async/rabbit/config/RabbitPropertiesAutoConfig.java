package org.reactivecommons.async.rabbit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.rabbitmq")
public class RabbitPropertiesAutoConfig extends RabbitPropertiesBase {
}
