package org.reactivecommons.async.impl.config.annotations;

import org.reactivecommons.async.starter.listeners.CommandsListenerConfig;
import org.reactivecommons.async.starter.listeners.EventsListenerConfig;
import org.reactivecommons.async.starter.listeners.NotificationEventsListenerConfig;
import org.reactivecommons.async.starter.listeners.QueriesListenerConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation enables all messages listeners (Query, Commands, Events). If you want to enable separately, please use
 * EnableCommandListeners, EnableQueryListeners or EnableEventListeners.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Documented
@Import({CommandsListenerConfig.class, QueriesListenerConfig.class, EventsListenerConfig.class,
        NotificationEventsListenerConfig.class})
@Configuration
public @interface EnableMessageListeners {
}



