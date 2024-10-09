package org.reactivecommons.async.starter.senders;

import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.ReactiveCommonsConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Configuration
@Import(ReactiveCommonsConfig.class)
public class EventBusConfig {

    @Bean
    public DomainEventBus genericDomainEventBus(ConnectionManager manager) {
        ConcurrentMap<String, DomainEventBus> domainEventBuses = new ConcurrentHashMap<>();
        manager.forDomain((domain, provider) -> domainEventBuses.put(domain, provider.getDomainBus()));
        return new GenericDomainEventBus(domainEventBuses);
    }
}
