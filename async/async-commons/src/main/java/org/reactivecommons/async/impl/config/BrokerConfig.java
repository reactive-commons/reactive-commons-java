package org.reactivecommons.async.impl.config;

import java.util.UUID;

public class BrokerConfig {
    private final String routingKey = UUID.randomUUID().toString().replaceAll("-", "");

    public String getRoutingKey() {
        return routingKey;
    }

}
