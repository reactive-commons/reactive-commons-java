package org.reactivecommons.async.starter.config;

import lombok.extern.log4j.Log4j2;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.starter.config.disabled.DisabledDirectAsyncGateway;
import org.reactivecommons.async.starter.config.disabled.DisabledDomainEventBus;
import org.reactivecommons.async.starter.senders.GenericDirectAsyncGateway;
import org.reactivecommons.async.starter.senders.GenericDomainEventBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Dynamic alternative to the static {@code @Enable*} annotations.
 * <p>
 * All beans in this configuration are only created when a {@link ReactiveCommonsFeatures} bean
 * is present in the application context. Each feature is then conditionally activated based
 * on the flags set in that bean.
 * <p>
 * Use {@code @EnableReactiveCommonsDynamic} (or {@code @Import(ReactiveCommonsDynamicConfig.class)})
 * together with a {@code @Bean} of type {@link ReactiveCommonsFeatures} in your application.
 */
@Log4j2
@Configuration
@Import({ReactiveCommonsConfig.class, ReactiveCommonsListenersConfig.class})
public class ReactiveCommonsDynamicConfig {

    // -------------------------------------------------------------------------
    // Listener activation — side-effect beans that start broker listeners
    // when the corresponding feature flag is true.
    // -------------------------------------------------------------------------

    @Bean
    @SuppressWarnings("rawtypes")
    public Object dynamicEventListenerActivator(ConnectionManager manager,
                                                DomainHandlers handlers,
                                                ReactiveCommonsDomainFeatures features) {
        manager.forDomain((domain, provider) -> {
            if (features.ofDomain(domain).isListenEvents()) {
                log.info("ReactiveCommons: activating event listeners for domain '{}'", domain);
                provider.listenDomainEvents(handlers.get(domain));
            }
        });
        return new Object();
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public Object dynamicNotificationListenerActivator(ConnectionManager manager,
                                                       DomainHandlers handlers,
                                                       ReactiveCommonsDomainFeatures features) {
        manager.forDomain((domain, provider) -> {
            if (features.ofDomain(domain).isListenNotificationEvents()) {
                log.info("ReactiveCommons: activating notification event listeners for domain '{}'", domain);
                provider.listenNotificationEvents(handlers.get(domain));
            }
        });
        return new Object();
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public Object dynamicCommandListenerActivator(ConnectionManager manager,
                                                  DomainHandlers handlers,
                                                  ReactiveCommonsDomainFeatures features) {
        manager.forDomain((domain, provider) -> {
            if (features.ofDomain(domain).isListenCommands()) {
                log.info("ReactiveCommons: activating command listeners for domain '{}'", domain);
                provider.listenCommands(handlers.get(domain));
            }
        });
        return new Object();
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public Object dynamicQueryListenerActivator(ConnectionManager manager,
                                                DomainHandlers handlers,
                                                ReactiveCommonsDomainFeatures features) {
        manager.forDomain((domain, provider) -> {
            if (features.ofDomain(domain).isListenQueries()) {
                log.info("ReactiveCommons: activating query listeners for domain '{}'", domain);
                provider.listenQueries(handlers.get(domain));
            }
        });
        return new Object();
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public Object dynamicQueueListenerActivator(ConnectionManager manager,
                                                DomainHandlers handlers,
                                                ReactiveCommonsDomainFeatures features) {
        manager.forDomain((domain, provider) -> {
            if (features.ofDomain(domain).isListenQueues()) {
                log.info("ReactiveCommons: activating queue listeners for domain '{}'", domain);
                provider.listenQueues(handlers.get(domain));
            }
        });
        return new Object();
    }

    // -------------------------------------------------------------------------
    // Sender beans — always created when ReactiveCommonsFeatures is present.
    // If the corresponding flag is false, calls return a Mono.error to signal
    // misconfiguration clearly at use-time rather than at startup.
    // -------------------------------------------------------------------------

    @Bean
    @SuppressWarnings("rawtypes")
    public DomainEventBus dynamicDomainEventBus(ConnectionManager manager,
                                                ReactiveCommonsDomainFeatures features) {
        ConcurrentMap<String, DomainEventBus> buses = new ConcurrentHashMap<>();
        manager.forDomain((domain, provider) -> {
            if (features.ofDomain(domain).isSendEvents()) {
                log.info("ReactiveCommons: activating DomainEventBus for domain '{}'", domain);
                buses.put(domain, provider.getDomainBus());
            }
        });
        if (buses.isEmpty()) {
            return new DisabledDomainEventBus();
        }
        return new GenericDomainEventBus(buses);
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public DirectAsyncGateway dynamicDirectAsyncGateway(ConnectionManager manager,
                                                        ReactiveCommonsDomainFeatures features) {
        ConcurrentMap<String, DirectAsyncGateway> gateways = new ConcurrentHashMap<>();
        manager.forDomain((domain, provider) -> {
            if (features.ofDomain(domain).isSendCommands()) {
                log.info("ReactiveCommons: activating DirectAsyncGateway for domain '{}'", domain);
                gateways.put(domain, provider.getDirectAsyncGateway());
            }
        });
        if (gateways.isEmpty()) {
            return new DisabledDirectAsyncGateway();
        }
        return new GenericDirectAsyncGateway(gateways);
    }

}
