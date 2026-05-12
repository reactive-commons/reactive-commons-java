package org.reactivecommons.async.starter.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.DefaultQueryHandler;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.utils.resolver.HandlerResolverBuilder;
import org.reactivecommons.async.starter.props.GenericAsyncPropsDomain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Map;

@Log
@Configuration
@RequiredArgsConstructor
@ComponentScan("org.reactivecommons.async.starter.impl.listener")
public class ReactiveCommonsListenersConfig {

    /**
     * Builds the {@link DomainHandlers} by resolving all registered {@link HandlerRegistry} beans against each
     * configured domain. The first configured domain always gets {@link HandlerRegistry#DEFAULT_DOMAIN} as
     * its alias, ensuring that all handlers registered via the standard API (which internally stores them under
     * the default key) are always resolved by the first domain, regardless of its actual name.
     */
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
        props.forEach((beanName, properties) -> {
            String defaultDomain = properties.getDefaultDomainName();
            log.info("DEFAULT_DOMAIN: " + defaultDomain);
            properties.forEach((domain, asyncProps) -> {
                String domainName = (String) domain;
                String aliasDomain = domainName.equals(defaultDomain) ? HandlerRegistry.getUnresolvedDomain() : null;
                log.info("DOMAIN: " + domainName);
                log.info("ALIAS_DOMAIN: " + aliasDomain);
                HandlerResolver resolver = HandlerResolverBuilder.buildResolver(
                        domainName, aliasDomain, registries, commandHandler
                );
                handlers.add(domainName, resolver);
            });
        });
        return handlers;
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
