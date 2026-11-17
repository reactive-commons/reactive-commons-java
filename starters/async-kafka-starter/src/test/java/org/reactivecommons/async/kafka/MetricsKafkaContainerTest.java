package org.reactivecommons.async.kafka;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.reactivecommons.async.impl.config.annotations.EnableTopicListeners;
import org.reactivecommons.async.kafka.metrics.KafkaDomainMetrics;
import org.reactivecommons.async.kafka.metrics.KafkaMetricsFactory;
import org.reactivecommons.async.kafka.metrics.MicrometerKafkaMetricsFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.body;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.publish;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.startOnOwnDomain;

/**
 * KC-17: the metrics of the Kafka clients ({@code kafka.consumer.*}, {@code kafka.producer.*}) and those of the
 * listeners are published in the {@code MeterRegistry} of the application, against a REAL Kafka broker, and removed
 * once the context closes. Without a {@code MeterRegistry} nothing is recorded and everything works as before.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class MetricsKafkaContainerTest {

    private static final String DOMAIN = "accounts";
    private static final String TOPIC = "metrics.topic";
    private static final Duration WAIT = Duration.ofSeconds(60);

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KafkaScenarioSupport.KAFKA_IMAGE);

    @Configuration
    @EnableTopicListeners
    @EnableDomainEventBus
    static class ListenAndSend {
    }

    @Configuration
    static class WithRegistry {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration
    static class WithoutMetrics {
        @Bean
        @Primary
        KafkaMetricsFactory kafkaMetricsFactory() {
            return domain -> KafkaDomainMetrics.NONE;
        }
    }

    /**
     * Handles every message but those whose body says {@code fail}.
     */
    private static HandlerRegistry handlers() {
        return HandlerRegistry.register().listenTopic(DOMAIN, TOPIC, message -> body(message).contains("fail")
                ? Mono.error(new IllegalStateException("dependency down"))
                : Mono.empty());
    }

    @Test
    void theClientAndListenerMetricsArePublishedAndRemovedOnClose() throws Exception {
        publish(KAFKA, TOPIC, "{\"ok\":1}");
        publish(KAFKA, TOPIC, "{\"fail\":1}");
        MeterRegistry registry;

        try (AnnotationConfigApplicationContext context = startOnOwnDomain(KAFKA, "metrics-app", DOMAIN,
                Map.of("maxRetries", "1", "retryDelay", "50"), handlers(), ListenAndSend.class, WithRegistry.class)) {
            registry = context.getBean(MeterRegistry.class);
            Mono.from(context.getBean(DomainEventBus.class).emit(DOMAIN, new DomainEvent<>(TOPIC, "1", Map.of())))
                    .block(Duration.ofSeconds(30));

            await().atMost(WAIT).untilAsserted(() -> {
                assertThat(count(registry, "reactive_commons.kafka.consumer.messages", "outcome", "ack"))
                        .as("handled: the published one and the emitted one").isEqualTo(2);
                assertThat(count(registry, "reactive_commons.kafka.consumer.messages", "outcome", "discard"))
                        .as("discarded").isEqualTo(1);
            });
            assertThat(count(registry, "reactive_commons.kafka.consumer.handler.failures", null, null))
                    .as("failed attempts").isEqualTo(2);
            assertThat(count(registry, "reactive_commons.kafka.consumer.retry.attempts", null, null))
                    .as("retries").isEqualTo(1);
            assertThat(registry.get("reactive_commons.kafka.consumer.handler.latency")
                    .tag("domain", DOMAIN).tag("topic", TOPIC).timer().count()).isEqualTo(3);
            assertThat(names(registry)).as("client metrics")
                    .anyMatch(name -> name.startsWith("kafka.consumer."))
                    .anyMatch(name -> name.startsWith("kafka.producer."));
            assertThat(registry.getMeters()).filteredOn(meter -> meter.getId().getName().startsWith("kafka."))
                    .allMatch(meter -> DOMAIN.equals(meter.getId().getTag("domain")));
        }

        await().atMost(WAIT).untilAsserted(() -> assertThat(names(registry)).as("meters left by the closed context")
                .noneMatch(name -> name.startsWith("kafka.consumer.") || name.startsWith("kafka.producer.")
                        || name.startsWith("reactive_commons.kafka.")));
    }

    /**
     * The metrics are optional: without them (no Micrometer {@code MeterRegistry}, or a factory that records nothing)
     * the listeners consume as before and nothing is published.
     */
    @Test
    void withoutMetricsNothingIsRecordedAndTheListenersWork() throws Exception {
        String topic = "metrics.none";
        List<String> received = new CopyOnWriteArrayList<>();
        publish(KAFKA, topic, "{\"ok\":1}");

        try (AnnotationConfigApplicationContext context = startOnOwnDomain(KAFKA, "no-metrics-app", DOMAIN,
                Map.of(), HandlerRegistry.register().listenTopic(DOMAIN, topic, message -> {
                    received.add(body(message));
                    return Mono.empty();
                }), ListenAndSend.class, WithRegistry.class, WithoutMetrics.class)) {
            await().atMost(WAIT).until(() -> received.size() == 1);
            assertThat(names(context.getBean(MeterRegistry.class)))
                    .noneMatch(name -> name.startsWith("kafka.") || name.startsWith("reactive_commons.kafka."));
        }
    }

    @Test
    void theFactoryRecordsNothingWithoutAMeterRegistry() {
        ObjectProvider<MeterRegistry> none = new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class);

        assertThat(new MicrometerKafkaMetricsFactory(none).forDomain(DOMAIN)).isSameAs(KafkaDomainMetrics.NONE);
    }

    private static double count(MeterRegistry registry, String name, String tag, String value) {
        var search = registry.find(name).tag("domain", DOMAIN).tag("topic", TOPIC);
        if (tag != null) {
            search = search.tag(tag, value);
        }
        var counter = search.counter();
        return counter == null ? 0 : counter.count();
    }

    private static List<String> names(MeterRegistry registry) {
        return registry.getMeters().stream().map(Meter::getId).map(Meter.Id::getName).toList();
    }
}
