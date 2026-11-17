package org.reactivecommons.async.kafka;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.config.annotations.EnableTopicListeners;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.body;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.committedOffset;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.createTopic;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.publish;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.startOnOwnDomain;

/**
 * When the listeners start and stop consuming, against a REAL Kafka broker: only once the application context is
 * refreshed, and, when it closes, after the messages being handled are handled and committed.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConsumerLifecycleKafkaContainerTest {

    private static final String DOMAIN = "accounts";
    private static final Duration WAIT = Duration.ofSeconds(30);
    /**
     * How long a bean of the application takes to initialize: longer than a consumer takes to join its group and
     * fetch the first records.
     */
    private static final Duration WARM_UP = Duration.ofSeconds(8);
    private static final AtomicBoolean WARMED_UP = new AtomicBoolean();

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KafkaScenarioSupport.KAFKA_IMAGE);

    @Configuration
    @EnableTopicListeners
    static class ListenTopics {
    }

    /**
     * A bean that takes a while to initialize, e.g. warming up a cache the handlers use.
     */
    @Configuration
    @EnableTopicListeners
    static class SlowToInitialize {
        @Bean
        SmartInitializingSingleton warmUp() {
            return () -> {
                try {
                    // Waits without any condition to wait for: it only takes time
                    assertThat(new CountDownLatch(1).await(WARM_UP.toMillis(), TimeUnit.MILLISECONDS)).isFalse();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                WARMED_UP.set(true);
            };
        }
    }

    @Test
    void noMessageIsHandledBeforeTheApplicationContextIsRefreshed() throws Exception {
        String topic = "lifecycle.refresh";
        createTopic(KAFKA, topic, 1);
        publish(KAFKA, topic, 0, "first");
        List<Boolean> warmedUpWhenHandled = new CopyOnWriteArrayList<>();
        HandlerRegistry handlers = HandlerRegistry.register().listenTopic(DOMAIN, topic, message -> {
            warmedUpWhenHandled.add(WARMED_UP.get());
            return Mono.empty();
        });

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA, "lifecycle-refresh", DOMAIN,
                Map.of(), handlers, SlowToInitialize.class)) {
            await().atMost(WAIT).until(() -> !warmedUpWhenHandled.isEmpty());
            assertThat(warmedUpWhenHandled).as("bean initialized when the handler ran").containsOnly(true);
        }
    }

    @Test
    void aMessageBeingHandledWhenTheContextClosesIsHandledCommittedAndNotDeliveredAgain() throws Exception {
        String topic = "lifecycle.drain";
        String group = "lifecycle-drain";
        createTopic(KAFKA, topic, 1);
        publish(KAFKA, topic, 0, "slow", "next");
        CountDownLatch slowStarted = new CountDownLatch(1);
        List<String> completed = new CopyOnWriteArrayList<>();
        HandlerRegistry handlers = HandlerRegistry.register().listenTopic(DOMAIN, topic, message -> {
            String value = body(message);
            if (value.equals("slow")) {
                slowStarted.countDown();
                return Mono.delay(Duration.ofSeconds(2)).doOnNext(ignored -> completed.add(value)).then();
            }
            completed.add(value);
            return Mono.empty();
        });

        AnnotationConfigApplicationContext context = startOnOwnDomain(KAFKA, group, DOMAIN, Map.of(), handlers,
                ListenTopics.class);
        assertThat(slowStarted.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).as("'slow' being handled").isTrue();
        context.close();

        assertThat(completed).as("messages handled before the context closed").containsExactly("slow");
        assertThat(committedOffset(KAFKA, group, topic, 0)).as("committed offset").isEqualTo(1);

        List<String> afterRestart = new CopyOnWriteArrayList<>();
        HandlerRegistry restarted = HandlerRegistry.register().listenTopic(DOMAIN, topic, message -> {
            afterRestart.add(body(message));
            return Mono.empty();
        });
        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA, group, DOMAIN, Map.of(),
                restarted, ListenTopics.class)) {
            await().atMost(WAIT).until(() -> committedOffset(KAFKA, group, topic, 0) == 2);
            assertThat(afterRestart).as("messages delivered after the restart").containsExactly("next");
        }
    }
}
