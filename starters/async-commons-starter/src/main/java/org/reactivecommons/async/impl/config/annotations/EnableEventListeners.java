package org.reactivecommons.async.impl.config.annotations;

import org.reactivecommons.async.starter.listeners.EventsListenerConfig;
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
@Import(EventsListenerConfig.class)
@Configuration
public @interface EnableEventListeners {
}



