package org.reactivecommons.async.rabbit.config.annotations;

import org.reactivecommons.async.rabbit.config.EventBusConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;


@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Documented
@Import(EventBusConfig.class)
@Configuration
public @interface EnableDomainEventBus {
}



