package org.reactivecommons.async.rabbit.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.reactivecommons.async.rabbit.RabbitMQSetupUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

class EventLoopGroupLifecycleManagerTest {

    private EventLoopGroupLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new EventLoopGroupLifecycleManager();
    }

    @Test
    void shouldStartAndReflectRunningState() {
        try (MockedStatic<RabbitMQSetupUtils> utils = mockStatic(RabbitMQSetupUtils.class)) {
            assertFalse(manager.isRunning());
            manager.start();
            assertTrue(manager.isRunning());
            utils.verify(RabbitMQSetupUtils::retainEventLoopGroup);
        }
    }

    @Test
    void shouldRetainOnlyOnceWhenStartedTwice() {
        try (MockedStatic<RabbitMQSetupUtils> utils = mockStatic(RabbitMQSetupUtils.class)) {
            manager.start();
            manager.start();
            utils.verify(RabbitMQSetupUtils::retainEventLoopGroup, times(1));
        }
    }

    @Test
    void shouldStopAndReleaseEventLoopGroup() {
        try (MockedStatic<RabbitMQSetupUtils> utils = mockStatic(RabbitMQSetupUtils.class)) {
            manager.start();
            manager.stop();
            utils.verify(RabbitMQSetupUtils::releaseEventLoopGroup);
            assertFalse(manager.isRunning());
        }
    }

    @Test
    void shouldNotReleaseWhenStopIsCalledAndNotRunning() {
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
