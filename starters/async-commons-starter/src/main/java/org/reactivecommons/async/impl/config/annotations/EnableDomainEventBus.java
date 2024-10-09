package org.reactivecommons.async.impl.config.annotations;

import org.reactivecommons.async.starter.senders.EventBusConfig;
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
@Import(EventBusConfig.class)
@Configuration
public @interface EnableDomainEventBus {
}



