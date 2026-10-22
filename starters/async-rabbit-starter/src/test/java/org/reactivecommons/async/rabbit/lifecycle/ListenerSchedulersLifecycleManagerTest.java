package org.reactivecommons.async.rabbit.lifecycle;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.rabbit.listeners.ListenerSchedulers;

import static org.assertj.core.api.Assertions.assertThat;

class ListenerSchedulersLifecycleManagerTest {

    @BeforeEach
    void setUp() {
        releaseEverything(); // other test classes of the JVM share the static schedulers
    }

    @AfterEach
    void tearDown() {
        releaseEverything();
    }

    @Test
    void shouldRegisterTheSchedulerGaugesOnStartAndRemoveThemOnStop() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ListenerSchedulersLifecycleManager manager = new ListenerSchedulersLifecycleManager(registry);

        manager.start();
        ListenerSchedulers.handlers(); // creates the pool, which registers the gauges for the retained registry

        assertThat(registry.find("reactive_commons.schedulers.handler.pool.size").gauge()).isNotNull();
        assertThat(registry.find("reactive_commons.schedulers.handler.growth").functionCounter()).isNotNull();

        manager.stop();

        assertThat(registry.find("reactive_commons.schedulers.handler.pool.size").gauge()).isNull();
        assertThat(ListenerSchedulers.activeApplications()).isZero();
    }

    @Test
    void shouldWorkWithoutARegistry() {
        ListenerSchedulersLifecycleManager manager = new ListenerSchedulersLifecycleManager();

        manager.start();
        assertThat(ListenerSchedulers.handlers()).isNotNull();

        manager.stop();
        assertThat(manager.isRunning()).isFalse();
        assertThat(ListenerSchedulers.activeApplications()).isZero();
    }

    private static void releaseEverything() {
        while (ListenerSchedulers.activeApplications() > 0) {
            ListenerSchedulers.release();
        }
    }
}
