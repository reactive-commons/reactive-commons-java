package org.reactivecommons.async.starter.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.DefaultQueryHandler;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.HandlerResolverBuilder;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.commons.converters.json.ObjectMapperSupplier;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.ext.DefaultCustomReporter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.broker.BrokerProviderFactory;
import org.reactivecommons.async.starter.broker.DiscardProvider;
import org.reactivecommons.async.starter.config.health.ReactiveCommonsHealthConfig;
import org.reactivecommons.async.starter.props.GenericAsyncProps;
import org.reactivecommons.async.starter.props.GenericAsyncPropsDomain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Log
@Configuration
@RequiredArgsConstructor
@Import(ReactiveCommonsHealthConfig.class)
@ComponentScan("org.reactivecommons.async.starter.impl")
public class ReactiveCommonsConfig {

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ConnectionManager buildConnectionManager(ApplicationContext context) {
        final Map<String, GenericAsyncPropsDomain> props = context.getBeansOfType(GenericAsyncPropsDomain.class);
        final Map<String, BrokerProviderFactory> providers = context.getBeansOfType(BrokerProviderFactory.class);

        ConnectionManager connectionManager = new ConnectionManager();
        props.forEach((beanName, domainProps) -> {
            final GenericAsyncProps defaultDomainProps = domainProps.getProps(DEFAULT_DOMAIN);
            domainProps.forEach((domain, asyncPropsObject) -> {
                String domainName = (String) domain;
                final GenericAsyncProps asyncProps = (GenericAsyncProps) asyncPropsObject;
                if (asyncProps.isEnabled()) {
                    BrokerProviderFactory factory = providers.get(asyncProps.getBrokerType());
                    if (!defaultDomainProps.isEnabled()) {
                        asyncProps.setUseDiscardNotifierPerDomain(true);
                    }
                    DiscardProvider discardProvider = factory.getDiscardProvider(defaultDomainProps);
                    BrokerProvider provider = factory.getProvider(domainName, asyncProps, discardProvider);
                    connectionManager.addDomain(domainName, provider);
                }
            });
        });
        return connectionManager;
    }

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
    public BrokerConfig brokerConfig() {
        return new BrokerConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapperSupplier objectMapperSupplier() {
        return new DefaultObjectMapperSupplier();
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
    @ConditionalOnMissingBean(HandlerRegistry.class)
    public HandlerRegistry defaultHandlerRegistry() {
        return HandlerRegistry.register();
    }

    @Bean
    @ConditionalOnMissingBean(ReactiveReplyRouter.class)
    public ReactiveReplyRouter defaultReactiveReplyRouter() {
        return new ReactiveReplyRouter();
    }

    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    public MeterRegistry defaultRabbitMeterRegistry() {
        return new SimpleMeterRegistry();
    }

}
