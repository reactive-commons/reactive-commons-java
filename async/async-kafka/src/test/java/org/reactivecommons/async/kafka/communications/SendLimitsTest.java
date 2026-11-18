package org.reactivecommons.async.kafka.communications;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SendLimitsTest {

    @Test
    void keepsPositiveLimits() {
        SendLimits limits = new SendLimits(Duration.ofSeconds(3), 5);

        assertThat(limits.sendTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(limits.maxPendingSends()).isEqualTo(5);
    }

    @Test
    void rejectsASendTimeoutThatIsNotPositive() {
        Duration negative = Duration.ofMillis(-1);

        assertThatThrownBy(() -> new SendLimits(null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sendTimeout must be positive: null");
        assertThatThrownBy(() -> new SendLimits(Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sendTimeout must be positive: PT0S");
        assertThatThrownBy(() -> new SendLimits(negative, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sendTimeout must be positive: PT-0.001S");
    }

    @Test
    void rejectsAMaxPendingSendsThatIsNotPositive() {
        Duration sendTimeout = Duration.ofSeconds(1);

        assertThatThrownBy(() -> new SendLimits(sendTimeout, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxPendingSends must be positive: 0");
    }
}
