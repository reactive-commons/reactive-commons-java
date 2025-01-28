package org.reactivecommons.async.commons.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class BrokerConfig {
    private final String routingKey = UUID.randomUUID().toString().replace("-", "");
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

}
