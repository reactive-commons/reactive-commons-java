package org.reactivecommons.async.rabbit.listeners;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumerMetricsTest {

    /**
     * A listener created after another one stopped, with the same tags (e.g. a restarted application whose registry
     * outlives it), gets gauges of its own instead of those bound to the stopped listener.
     */
    @Test
    void closingRemovesTheMetersSoTheNextListenerWithTheSameTagsGetsItsOwnGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConsumerMetrics stopped = new ConsumerMetrics(registry, "app", "queue", new ListenerLiveness("queue"));
        stopped.recordDeliveryStarted();
        stopped.recordOutcome(ConsumerMetrics.OUTCOME_ACK);
        stopped.recordHandlerExecution("handler", ConsumerMetrics.EXECUTION_SUCCESS, java.time.Duration.ofMillis(1));
        assertThat(registry.getMeters()).isNotEmpty();

        stopped.close();
        assertThat(registry.getMeters()).as("meters of the stopped listener").isEmpty();

        ConsumerMetrics next = new ConsumerMetrics(registry, "app", "queue", new ListenerLiveness("queue"));
        next.recordDeliveryStarted();
        next.recordDeliveryStarted();
        Gauge inFlight = registry.find("reactive_commons.consumer.in_flight").tags("queue", "queue").gauge();
        assertThat(inFlight).isNotNull();
        assertThat(inFlight.value()).as("in_flight of the new listener").isEqualTo(2.0);
    }

    @Test
    void theRepliesLostCounterOfADomainIsReleasedWithItsReplyListener() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConsumerMetrics app = ConsumerMetrics.forReplyListener(registry, "app", "replies-1", new ListenerLiveness("r1"));
        ConsumerMetrics other = ConsumerMetrics.forReplyListener(registry, "other", "replies-2",
                new ListenerLiveness("r2"));

        app.close();

        assertThat(registry.find("reactive_commons.consumer.replies.lost").tags("domain", "app").counter()).isNull();
        assertThat(registry.find("reactive_commons.consumer.replies.lost").tags("domain", "other").counter())
                .isNotNull();
        other.close();
    }

    /**
     * The default command handler is registered without a path: its executions get a named tag value, since
     * Prometheus reads an empty one as a missing label.
     */
    @Test
    void theExecutionsOfAHandlerRegisteredWithoutAPathAreTaggedUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConsumerMetrics metrics = new ConsumerMetrics(registry, "app", "queue", new ListenerLiveness("queue"));

        metrics.recordHandlerExecution("", ConsumerMetrics.EXECUTION_SUCCESS, java.time.Duration.ofMillis(1));

        assertThat(registry.find("reactive_commons.consumer.handler.execution").timers())
                .extracting(timer -> timer.getId().getTag("handler")).containsExactly(ConsumerMetrics.UNKNOWN_HANDLER);
        metrics.close();
    }
}
