package org.reactivecommons.async.impl.config.annotations;

import org.reactivecommons.async.impl.config.MessageListenersConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;


@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Documented
@Import(MessageListenersConfig.class)
@Configuration
public @interface EnableMessageListeners {
}



