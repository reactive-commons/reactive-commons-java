package org.reactivecommons.async.rabbit.discard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQDiscardProviderConfig {

    @Bean
    @ConditionalOnMissingBean(RabbitMQDiscardProviderFactory.class)
    public RabbitMQDiscardProviderFactory defaultRabbitMQDiscardProviderFactory() {
        return RabbitMQDiscardProviderImpl::new;
    }
}
