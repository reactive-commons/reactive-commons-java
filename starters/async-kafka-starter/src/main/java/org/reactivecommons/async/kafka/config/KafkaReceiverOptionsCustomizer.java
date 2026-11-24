package org.reactivecommons.async.kafka.config;

import reactor.kafka.receiver.ReceiverOptions;

/**
 * Adjusts the reactor-kafka options of the consumers of a domain, for the settings that have no property, e.g.
 * {@code commitInterval}, {@code commitBatchSize}, {@code maxDelayRebalance} or {@code pollTimeout}. Declare it as a
 * bean; several ones are applied in their order ({@code @Order}).
 * <p>
 * It is applied after the library sets its values, and some of them are set again afterwards because the delivery
 * depends on them: {@code enable.auto.commit} stays {@code false} (an offset must only be committed once its message
 * was handled). The subscription or assignment set here is replaced by each listener, and the consumers of the
 * notifications, which have no consumer group, only keep the client metrics, {@code pollTimeout} and
 * {@code closeTimeout}.
 */
@FunctionalInterface
public interface KafkaReceiverOptionsCustomizer {

    /**
     * @param domain  the domain whose consumers use the options
     * @param options the options built by the library from the properties of the domain
     * @return the options to use
     */
    ReceiverOptions<String, byte[]> customize(String domain, ReceiverOptions<String, byte[]> options);
}
