package org.reactivecommons.async.starter.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.commons.exceptions.MissingHandlerException;
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
        props.forEach((beanName, properties) -> properties
                .forEach((domain, asyncProps) -> {
                    String domainName = (String) domain;
                    HandlerResolver resolver = HandlerResolverBuilder.buildResolver(
                            domainName, registries, commandHandler
                    );
                    handlers.add(domainName, resolver);
                })
        );
        return handlers;
    }

    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("rawtypes")
    public DefaultQueryHandler defaultHandler() {
        // a query without handler can never be served: listeners discard it instead of retrying it
        return (DefaultQueryHandler<Object, Object>) query ->
                Mono.error(new MissingHandlerException("query", "(served by the default handler)"));
    }

    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("rawtypes")
    public DefaultCommandHandler defaultCommandHandler() {
        // a command without handler can never be handled: listeners discard it instead of retrying it
        return command -> Mono.error(new MissingHandlerException("command", commandName(command)));
    }

    private static String commandName(Object command) {
        return command instanceof org.reactivecommons.api.domain.Command<?> domainCommand
                ? domainCommand.getName() : String.valueOf(command);
    }

    @Bean
    @ConditionalOnMissingBean
    public HandlerRegistry defaultHandlerRegistry() {
        return HandlerRegistry.register();
    }
}
