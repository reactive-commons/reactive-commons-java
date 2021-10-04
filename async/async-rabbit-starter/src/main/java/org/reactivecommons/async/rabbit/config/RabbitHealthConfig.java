package org.reactivecommons.async.rabbit.config;

import org.reactivecommons.async.rabbit.health.RabbitReactiveHealthIndicator;
import org.springframework.boot.actuate.health.AbstractReactiveHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(AbstractReactiveHealthIndicator.class)
public class RabbitHealthConfig {

    @Bean
    public RabbitReactiveHealthIndicator rabbitHealthIndicator(ConnectionFactoryProvider provider) {
        return new RabbitReactiveHealthIndicator(provider);
    }
}
