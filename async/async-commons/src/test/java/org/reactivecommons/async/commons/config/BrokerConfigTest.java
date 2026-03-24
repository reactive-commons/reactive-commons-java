package org.reactivecommons.async.commons.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BrokerConfigTest {

    @Test
    void defaultConstructor() {
        var config = new BrokerConfig();
        assertThat(config.isPersistentQueries()).isFalse();
        assertThat(config.isPersistentCommands()).isTrue();
        assertThat(config.isPersistentEvents()).isTrue();
        assertThat(config.getReplyTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(config.getRoutingKey()).isNotBlank();
    }

    @Test
    void allArgsConstructor() {
        var config = new BrokerConfig(true, false, true, Duration.ofSeconds(30));
        assertThat(config.isPersistentQueries()).isTrue();
        assertThat(config.isPersistentCommands()).isFalse();
        assertThat(config.isPersistentEvents()).isTrue();
        assertThat(config.getReplyTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void routingKeyIsGenerated() {
        var c1 = new BrokerConfig();
        var c2 = new BrokerConfig();
        assertThat(c1.getRoutingKey()).isNotEqualTo(c2.getRoutingKey());
    }
}
