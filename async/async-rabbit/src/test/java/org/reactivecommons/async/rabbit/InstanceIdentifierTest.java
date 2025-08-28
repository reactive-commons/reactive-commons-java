package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceIdentifierTest {

    @Test
    void shouldGetInstanceIdFromUuid() {
        String instanceId = InstanceIdentifier.getInstanceId("events");
        var expectedLength = 39;
        assertThat(instanceId).endsWith("-events").hasSize(expectedLength);
    }

    @Test
    void shouldGetInstanceIdFromEnv() {
        String instanceId = InstanceIdentifier.getInstanceId("events", "host123");
        assertThat(instanceId).isEqualTo("host123-events");
    }
}
