package org.reactivecommons.async.starter.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.RequiredArgsConstructor;
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
import org.reactivecommons.async.starter.exceptions.InvalidConfigurationException;
import org.reactivecommons.async.starter.props.GenericAsyncProps;
import org.reactivecommons.async.starter.props.GenericAsyncPropsDomain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Configuration
@RequiredArgsConstructor
@Import(ReactiveCommonsHealthConfig.class)
@ComponentScan("org.reactivecommons.async.starter.impl.common")
public class ReactiveCommonsConfig {

    /**
     * Where each broker starter binds its domains, to tell how to disable one of them.
     */
    private static final Map<String, String> CONFIGURATION_PREFIXES = Map.of(
            "rabbitmq", "app.async",
            "kafka", "reactive.commons.kafka");

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ConnectionManager buildConnectionManager(ApplicationContext context) {
        final Map<String, GenericAsyncPropsDomain> props = context.getBeansOfType(GenericAsyncPropsDomain.class);
        final Map<String, BrokerProviderFactory> providers = context.getBeansOfType(BrokerProviderFactory.class);
        validateOneBrokerPerDomain(props.values());

        ConnectionManager connectionManager = new ConnectionManager();
        props.forEach((beanName, domainProps) -> {
            final GenericAsyncProps defaultDomainProps = domainProps.getProps(DEFAULT_DOMAIN);
            // ONE discard provider shared by all the domains of this broker type, not one per domain
            final Map<BrokerProviderFactory, DiscardProvider> discardProviders = new HashMap<>();
            domainProps.forEach((domain, asyncPropsObject) -> {
                String domainName = (String) domain;
                final GenericAsyncProps asyncProps = (GenericAsyncProps) asyncPropsObject;
                if (asyncProps.isEnabled()) {
                    BrokerProviderFactory factory = providers.get(asyncProps.getBrokerType());
                    if (!defaultDomainProps.isEnabled()) {
                        asyncProps.setUseDiscardNotifierPerDomain(true);
                    }
                    DiscardProvider discardProvider = discardProviders.computeIfAbsent(factory,
                            f -> f.getDiscardProvider(defaultDomainProps));
                    BrokerProvider provider = factory.getProvider(domainName, asyncProps, discardProvider);
                    connectionManager.addDomain(domainName, provider);
                }
            });
        });
        return connectionManager;
    }

    /**
     * Every broker starter requires its own 'app' domain, and the connection manager keeps one provider per domain
     * name: a domain enabled in two brokers would make one of them replace the other silently, leaving the replaced
     * one with its connections open and its listeners never started. Fails before any connection is opened.
     */
    @SuppressWarnings("rawtypes")
    static void validateOneBrokerPerDomain(Collection<GenericAsyncPropsDomain> brokers) {
        final Map<String, String> brokerOfDomain = new HashMap<>();
        brokers.forEach(domains -> domains.forEach((domain, asyncProps) -> {
            final GenericAsyncProps props = (GenericAsyncProps) asyncProps;
            if (!props.isEnabled()) {
                return;
            }
            final String domainName = (String) domain;
            final String other = brokerOfDomain.putIfAbsent(domainName, props.getBrokerType());
            if (other != null) {
                throw new InvalidConfigurationException("Domain '" + domainName + "' is enabled for both '" + other
                        + "' and '" + props.getBrokerType() + "'. A domain belongs to a single broker: disable it "
                        + "in one of them (" + enabledProperty(other, domainName) + " or "
                        + enabledProperty(props.getBrokerType(), domainName) + ").");
            }
        }));
    }

    private static String enabledProperty(String brokerType, String domain) {
        final String prefix = CONFIGURATION_PREFIXES.getOrDefault(brokerType, brokerType);
        return prefix + "." + domain + ".enabled: false";
    }

    @Bean
    @ConditionalOnMissingBean
    public BrokerProviderLifecycleManager brokerProviderLifecycleManager(ConnectionManager connectionManager) {
        return new BrokerProviderLifecycleManager(connectionManager);
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
    public JsonMapper defaultReactiveCommonsObjectMapper(ObjectMapperSupplier supplier) {
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
