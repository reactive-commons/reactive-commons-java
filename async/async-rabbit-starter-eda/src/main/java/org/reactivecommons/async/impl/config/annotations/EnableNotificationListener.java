package org.reactivecommons.async.impl.config.annotations;

import org.reactivecommons.async.rabbit.config.NotificationListenersConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;


@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Documented
@Import(NotificationListenersConfig.class)
@Configuration
public @interface EnableNotificationListener {
}



