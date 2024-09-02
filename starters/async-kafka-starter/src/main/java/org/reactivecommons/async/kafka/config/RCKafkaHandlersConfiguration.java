package org.reactivecommons.async.kafka.config;


import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.HandlerResolverBuilder;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class RCKafkaHandlersConfiguration {

    @Bean
    public DomainHandlers buildHandlers(AsyncKafkaPropsDomain props, ApplicationContext context,
                                        HandlerRegistry primaryRegistry, DefaultCommandHandler<?> commandHandler) {
        DomainHandlers handlers = new DomainHandlers();
        final Map<String, HandlerRegistry> registries = context.getBeansOfType(HandlerRegistry.class);
        if (!registries.containsValue(primaryRegistry)) {
            registries.put("primaryHandlerRegistry", primaryRegistry);
        }
        props.forEach((domain, properties) -> {
            HandlerResolver resolver = HandlerResolverBuilder.buildResolver(domain, registries, commandHandler);
            handlers.add(domain, resolver);
        });
        return handlers;
    }
}
