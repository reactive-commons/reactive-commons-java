package org.reactivecommons.async.starter.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
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
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Log
@Configuration
@RequiredArgsConstructor
@Import(ReactiveCommonsHealthConfig.class)
@ComponentScan("org.reactivecommons.async.starter.impl.common")
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
    public ObjectMapper defaultReactiveCommonsObjectMapper(ObjectMapperSupplier supplier) {
        return supplier.get();
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

    @Bean
    @ConditionalOnMissingBean
    public CustomReporter reactiveCommonsCustomErrorReporter() {
        return new DefaultCustomReporter();
    }

}
