package reactor.rabbitmq;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HelpersTest {

    @Test
    void safelyExecuteRunsAction() {
        var logger = mock(Logger.class);
        var ran = new AtomicBoolean(false);
        Helpers.safelyExecute(logger, () -> ran.set(true), "test");
        assertThat(ran).isTrue();
        verify(logger, never()).warn(anyString(), anyString(), anyString());
    }

    @Test
    void safelyExecuteLogsExceptionAndDoesNotThrow() {
        var logger = mock(Logger.class);
        Helpers.safelyExecute(logger, () -> {
            throw new RuntimeException("boom");
        }, "action failed");
        verify(logger).warn("{}: {}", "action failed", "boom");
    }
}
