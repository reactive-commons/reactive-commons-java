package org.reactivecommons.async.starter.senders;

import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.ReactiveCommonsConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Log
@Configuration
@RequiredArgsConstructor
@Import(ReactiveCommonsConfig.class)
public class DirectAsyncGatewayConfig {

    @Bean
    public DirectAsyncGateway genericDirectAsyncGateway(ConnectionManager manager) {
        ConcurrentMap<String, DirectAsyncGateway> directAsyncGateways = new ConcurrentHashMap<>();
        manager.forDomain((domain, provider) -> directAsyncGateways.put(domain,
                provider.getDirectAsyncGateway()));
        return new GenericDirectAsyncGateway(directAsyncGateways);
    }
}
