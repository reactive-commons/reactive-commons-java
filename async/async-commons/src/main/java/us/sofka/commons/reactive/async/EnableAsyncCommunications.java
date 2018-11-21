package us.sofka.commons.reactive.async;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Use when no spring.factories exist in classpath (this file is only an example)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Documented
@Import(MessageConfig.class)
@Configuration
public @interface EnableAsyncCommunications {
}



