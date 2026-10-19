package org.reactivecommons.async.rabbit.listeners;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriberRegistryTest {

    @Test
    void returnsSameLivenessForAGivenQueue() {
        var registry = new SubscriberRegistry();

        ListenerLiveness first = registry.register("app.events");
        ListenerLiveness second = registry.register("app.events");

        assertThat(second).isSameAs(first);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void differentQueuesGetDifferentLivenesses() {
        var registry = new SubscriberRegistry();

        ListenerLiveness events = registry.register("app.events");
        ListenerLiveness commands = registry.register("app.commands");

        assertThat(events).isNotSameAs(commands);
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void snapshotReflectsCurrentStateOfEveryRegisteredListener() {
        var registry = new SubscriberRegistry();
        ListenerLiveness events = registry.register("app.events");
        ListenerLiveness commands = registry.register("app.commands");
        events.onSubscribed("tag-events");
        commands.onSubscribed("tag-commands");
        events.onDelivery();

        var snapshot = registry.snapshot();

        assertThat(snapshot).containsOnlyKeys("app.events", "app.commands");
        assertThat(snapshot.get("app.events").active()).isTrue();
        assertThat(snapshot.get("app.events").lastDeliveryAt()).isNotNull();
        assertThat(snapshot.get("app.commands").active()).isTrue();
        assertThat(snapshot.get("app.commands").lastDeliveryAt()).isNull();
    }

    @Test
    void snapshotIsEmptyWhenNothingRegistered() {
        assertThat(new SubscriberRegistry().snapshot()).isEmpty();
    }

    @Test
    void unregisterRemovesTheQueue() {
        var registry = new SubscriberRegistry();
        registry.register("app.events");

        registry.unregister("app.events");

        assertThat(registry.size()).isZero();
        assertThat(registry.snapshot()).isEmpty();
    }
}
