package org.reactivecommons.async.impl.config.annotations;

import org.reactivecommons.async.impl.config.CommandListenersConfig;
import org.reactivecommons.async.impl.config.EventListenersConfig;
import org.reactivecommons.async.impl.config.QueryListenerConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Actualmente se utiliza EnableMessageListeners para habilitar Comandos, querys y eventos al mismo tiempo,
 * se han separado en 3 EnableCommandListeners, EnableQueryListeners y EnableEventListeners, estos se pueden utilizar
 * todos juntos o de manera individual segun necesidad
 * @deprecated Use EnableCommandListeners, EnableQueryListeners, EnableEventListeners
 */
@Deprecated
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Documented
@Import({CommandListenersConfig.class, QueryListenerConfig.class, EventListenersConfig.class})
@Configuration
public @interface EnableMessageListeners {
}



