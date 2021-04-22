package org.reactivecommons.async.impl.config.annotations;

import org.reactivecommons.async.rabbit.config.DirectAsyncGatewayConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;


@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Documented
@Import(DirectAsyncGatewayConfig.class)
@Configuration
public @interface EnableDirectAsyncGateway {
}



