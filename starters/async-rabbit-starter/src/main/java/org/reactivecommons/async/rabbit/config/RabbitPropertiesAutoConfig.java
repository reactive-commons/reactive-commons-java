package org.reactivecommons.async.rabbit.config;

import org.reactivecommons.async.rabbit.config.spring.RabbitPropertiesBase;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Primary;

@ConfigurationProperties(prefix = "spring.rabbitmq")
public class RabbitPropertiesAutoConfig extends RabbitPropertiesBase {
}
