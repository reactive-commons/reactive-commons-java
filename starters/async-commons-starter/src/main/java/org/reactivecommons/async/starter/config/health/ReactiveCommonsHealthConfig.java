package org.reactivecommons.async.starter.config.health;

import org.reactivecommons.async.starter.config.ConnectionManager;
import org.springframework.boot.actuate.health.AbstractReactiveHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(AbstractReactiveHealthIndicator.class)
public class ReactiveCommonsHealthConfig {

    @Bean
    @ConditionalOnProperty(prefix = "management.health.reactive-commons", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public ReactiveCommonsHealthIndicator reactiveCommonsHealthIndicator(ConnectionManager manager) {
        return new ReactiveCommonsHealthIndicator(manager);
    }
}
