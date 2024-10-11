package org.reactivecommons.async.commons.config;

import lombok.Getter;

import java.time.Duration;
import java.util.UUID;

@Getter
public class BrokerConfig {
    private final String routingKey = UUID.randomUUID().toString().replaceAll("-", "");
    private final boolean persistentQueries;
    private final boolean persistentCommands;
    private final boolean persistentEvents;
    private final Duration replyTimeout;

    public BrokerConfig() {
        this.persistentQueries = false;
        this.persistentCommands = true;
        this.persistentEvents = true;
        this.replyTimeout = Duration.ofSeconds(15);
    }

    public BrokerConfig(boolean persistentQueries, boolean persistentCommands, boolean persistentEvents, Duration replyTimeout) {
        this.persistentQueries = persistentQueries;
        this.persistentCommands = persistentCommands;
        this.persistentEvents = persistentEvents;
        this.replyTimeout = replyTimeout;
    }

}
