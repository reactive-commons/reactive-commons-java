package org.reactivecommons.async.kafka.communications.topology;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * How long the {@link TopologyCreator} remembers what it found about a topic, and the clock it measures it with.
 *
 * @param missingTtl  how long a topic found missing is reported missing without asking the cluster again
 * @param existingTtl how long a topic found (or created) is taken as existing without asking the cluster again
 * @param nanoClock   the time, in nanoseconds, as {@link System#nanoTime()} gives it
 */
record TopicCache(Duration missingTtl, Duration existingTtl, LongSupplier nanoClock) {

    static final TopicCache DEFAULT = new TopicCache(TopologyCreator.MISSING_TOPIC_TTL,
            TopologyCreator.EXISTING_TOPIC_TTL, System::nanoTime);
}
