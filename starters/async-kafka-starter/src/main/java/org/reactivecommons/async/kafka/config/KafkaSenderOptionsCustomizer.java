package org.reactivecommons.async.kafka.config;

import reactor.kafka.sender.SenderOptions;

/**
 * Adjusts the reactor-kafka options of the producer of a domain, for the settings that have no property, e.g.
 * {@code maxInFlight} or {@code closeTimeout}. Declare it as a bean; several ones are applied in their order
 * ({@code @Order}). It also applies to the producer that publishes the discarded messages of the domains that share
 * the discard notifier of the {@code app} domain.
 * <p>
 * It is applied after the library sets its values, and some of them are set again afterwards because the delivery
 * depends on them: {@code stopOnError} stays {@code false} (with {@code true} a single failed record would fail every
 * send waiting for its outcome and stop the producer). The producer properties it sets are checked as the Kafka
 * producer reads them: a value it rejects fails the startup.
 */
@FunctionalInterface
public interface KafkaSenderOptionsCustomizer {

    /**
     * @param domain  the domain whose producer uses the options
     * @param options the options built by the library from the properties of the domain
     * @return the options to use
     */
    SenderOptions<String, byte[]> customize(String domain, SenderOptions<String, byte[]> options);
}
