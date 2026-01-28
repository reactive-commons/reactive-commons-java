package org.reactivecommons.async.rabbit.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.reactivecommons.async.rabbit.RabbitMQSetupUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

class EventLoopGroupLifecycleManagerTest {

    private EventLoopGroupLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new EventLoopGroupLifecycleManager();
    }

    @Test
    void shouldStartAndReflectRunningState() {
        assertFalse(manager.isRunning());
        manager.start();
        assertTrue(manager.isRunning());
    }

    @Test
    void shouldStopAndCallShutdownEventLoopGroup() {
        manager.start();
        try (MockedStatic<RabbitMQSetupUtils> utils = mockStatic(RabbitMQSetupUtils.class)) {
            manager.stop();
            utils.verify(RabbitMQSetupUtils::shutdownEventLoopGroup);
            assertFalse(manager.isRunning());
        }
    }

    @Test
    void shouldNotCallShutdownWhenStopIsCalledAndNotRunning() {
        try (MockedStatic<RabbitMQSetupUtils> utils = mockStatic(RabbitMQSetupUtils.class)) {
            manager.stop();
            utils.verifyNoInteractions();
            assertFalse(manager.isRunning());
        }
    }

    @Test
    void shouldReturnPhaseMinusTen() {
        assertEquals(-10, manager.getPhase());
    }
}
