package org.reactivecommons.async.rabbit.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.reactivecommons.async.rabbit.RabbitMQSetupUtils;

import static org.junit.jupiter.api.Assertions.*;

class EventLoopGroupLifecycleManagerTest {

    private EventLoopGroupLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new EventLoopGroupLifecycleManager();
    }

    @Test
    void testStartAndIsRunning() {
        assertFalse(manager.isRunning());
        manager.start();
        assertTrue(manager.isRunning());
    }

    @Test
    void testStopCallsShutdownEventLoopGroup() {
        manager.start();
        try (MockedStatic<RabbitMQSetupUtils> utils = org.mockito.Mockito.mockStatic(RabbitMQSetupUtils.class)) {
            manager.stop();
            utils.verify(RabbitMQSetupUtils::shutdownEventLoopGroup);
            assertFalse(manager.isRunning());
        }
    }

    @Test
    void testStopWhenNotRunningDoesNotCallShutdown() {
        try (MockedStatic<RabbitMQSetupUtils> utils = org.mockito.Mockito.mockStatic(RabbitMQSetupUtils.class)) {
            manager.stop();
            utils.verifyNoInteractions();
            assertFalse(manager.isRunning());
        }
    }

    @Test
    void testGetPhase() {
        assertEquals(-10, manager.getPhase());
    }
}
