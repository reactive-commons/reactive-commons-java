package org.reactivecommons.async.impl.config.annotations;

import org.reactivecommons.async.starter.config.ReactiveCommonsDynamicConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables runtime-configurable reactive-commons features driven by a
 * {@link org.reactivecommons.async.starter.config.ReactiveCommonsFeatures} bean.
 * <p>
 * Place this annotation on any {@code @Configuration} class (typically your
 * {@code @SpringBootApplication}) and declare a
 * {@link org.reactivecommons.async.starter.config.ReactiveCommonsFeatures} bean
 * to control which features are activated at runtime:
 *
 * <pre>{@code
 * @EnableReactiveCommonsDynamic
 * @SpringBootApplication
 * public class MyApplication { ... }
 *
 * @Bean
 * public ReactiveCommonsFeatures reactiveCommonsFeatures() {
 *     boolean needsEvents = someService.needsEventListening();
 *     return ReactiveCommonsFeatures.builder()
 *         .listenEvents(needsEvents)
 *         .sendEvents(true)
 *         .listenCommands(true)
 *         .sendCommands(true)
 *         .build();
 * }
 * }</pre>
 *
 * <p>This is a dynamic alternative to combining the static annotations
 * ({@code @EnableEventListeners}, {@code @EnableCommandListeners},
 * {@code @EnableDomainEventBus}, etc.). Both approaches coexist — no changes
 * are needed to existing code that uses the static annotations.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Documented
@Import(ReactiveCommonsDynamicConfig.class)
@Configuration
public @interface EnableReactiveCommonsDynamic {
}
