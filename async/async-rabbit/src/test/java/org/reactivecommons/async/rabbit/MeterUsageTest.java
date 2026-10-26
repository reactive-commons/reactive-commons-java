package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MeterUsageTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void aMeterIsRemovedOnceNoOwnerUsesItAndAGaugeFollowsTheOwnerStillUsingIt() {
        Object first = new Object();
        Object second = new Object();
        AtomicInteger firstValue = new AtomicInteger(1);
        AtomicInteger secondValue = new AtomicInteger(2);
        Counter counter = MeterUsage.register(registry, first, () -> Counter.builder("c").register(registry));
        MeterUsage.register(registry, first, () -> Gauge.builder("g", firstValue, AtomicInteger::get)
                .register(registry));
        assertThat(MeterUsage.register(registry, second, () -> Counter.builder("c").register(registry)))
                .as("the counter registered with the same name and tags").isSameAs(counter);
        MeterUsage.register(registry, second, () -> Gauge.builder("g", secondValue, AtomicInteger::get)
                .register(registry));
        counter.increment();
        assertThat(registry.get("g").gauge().value()).as("bound to the first owner").isEqualTo(1.0);

        MeterUsage.release(registry, first);

        assertThat(registry.get("c").counter().count()).as("the counter keeps its count").isEqualTo(1.0);
        assertThat(registry.get("g").gauge().value()).as("bound to the second owner").isEqualTo(2.0);

        MeterUsage.release(registry, second);

        assertThat(registry.getMeters()).as("meters no owner uses").isEmpty();
    }
}
