package org.reactivecommons.async.starter.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.DefaultQueryHandler;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.HandlerResolverBuilder;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.ext.DefaultCustomReporter;
import org.reactivecommons.async.starter.props.GenericAsyncPropsDomain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Map;

@Log
@Configuration
@RequiredArgsConstructor
public class ReactiveCommonsListenersConfig {

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public DomainHandlers buildHandlers(ApplicationContext context,
                                        HandlerRegistry primaryRegistry, DefaultCommandHandler<?> commandHandler) {
        DomainHandlers handlers = new DomainHandlers();
        final Map<String, HandlerRegistry> registries = context.getBeansOfType(HandlerRegistry.class);
        if (!registries.containsValue(primaryRegistry)) {
            registries.put("primaryHandlerRegistry", primaryRegistry);
        }
        final Map<String, GenericAsyncPropsDomain> props = context.getBeansOfType(GenericAsyncPropsDomain.class);
        props.forEach((beanName, properties) -> properties.forEach((domain, asyncProps) -> {
            String domainName = (String) domain;
            HandlerResolver resolver = HandlerResolverBuilder.buildResolver(domainName, registries, commandHandler);
            handlers.add(domainName, resolver);
        }));
        return handlers;
    }

    @Bean
    @ConditionalOnMissingBean
    public CustomReporter reactiveCommonsCustomErrorReporter() {
        return new DefaultCustomReporter();
    }

    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("rawtypes")
    public DefaultQueryHandler defaultHandler() {
        return (DefaultQueryHandler<Object, Object>) command ->
                Mono.error(new RuntimeException("No Handler Registered"));
    }

    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("rawtypes")
    public DefaultCommandHandler defaultCommandHandler() {
        return message -> Mono.error(new RuntimeException("No Handler Registered"));
    }

    @Bean
    @ConditionalOnMissingBean
    public HandlerRegistry defaultHandlerRegistry() {
        return HandlerRegistry.register();
    }
}
