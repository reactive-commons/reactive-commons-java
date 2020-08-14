package org.reactivecommons.async.impl.config.annotations;

import org.reactivecommons.async.impl.config.CommandListenersConfig;
import org.reactivecommons.async.impl.config.EventListenersConfig;
import org.reactivecommons.async.impl.config.NotificacionListenersConfig;
import org.reactivecommons.async.impl.config.QueryListenerConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * This annotation enables all messages listeners (Query, Commands, Events). If you want to enable separately, please use
 * EnableCommandListeners, EnableQueryListeners or EnableEventListeners.
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Documented
@Import({CommandListenersConfig.class, QueryListenerConfig.class, EventListenersConfig.class, NotificacionListenersConfig.class})
@Configuration
public @interface EnableMessageListeners {
}



