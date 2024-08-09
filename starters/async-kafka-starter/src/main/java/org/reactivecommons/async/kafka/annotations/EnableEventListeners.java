package org.reactivecommons.async.kafka.annotations;

import org.reactivecommons.async.kafka.config.RCKafkaConfig;
import org.reactivecommons.async.kafka.config.RCKafkaEventListenerConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Documented
@Import({RCKafkaEventListenerConfig.class, RCKafkaConfig.class})
@Configuration
public @interface EnableEventListeners {
}



