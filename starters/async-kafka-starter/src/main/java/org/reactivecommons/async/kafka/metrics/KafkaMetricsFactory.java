package org.reactivecommons.async.kafka.metrics;

/**
 * Creates the metrics of each Kafka domain. The starter declares one backed by Micrometer when Micrometer is in the
 * classpath; it records nothing when there is no {@code MeterRegistry} bean.
 */
@FunctionalInterface
public interface KafkaMetricsFactory {

    /**
     * @param domain the domain whose clients and listeners report to the metrics
     */
    KafkaDomainMetrics forDomain(String domain);
}
