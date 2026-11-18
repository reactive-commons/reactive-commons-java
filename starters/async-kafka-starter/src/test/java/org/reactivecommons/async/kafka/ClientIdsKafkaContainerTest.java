package org.reactivecommons.async.kafka;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.reactivecommons.async.impl.config.annotations.EnableEventListeners;
import org.reactivecommons.async.impl.config.annotations.EnableTopicListeners;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import reactor.core.publisher.Mono;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.publish;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.startOnOwnDomain;

/**
 * KC-24: the producer, each consumer and the admin client of a domain have a {@code client.id} of their own, against a
 * REAL Kafka broker: their JMX beans and metrics do not collide, as they did when all of them used the
 * {@code appName}. The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ClientIdsKafkaContainerTest {

    private static final String DOMAIN = "accounts";
    private static final String EVENT = "client-ids.event";
    private static final String TOPIC = "client-ids.topic";
    private static final String CLIENT_ID = "kc24-client";
    private static final AtomicInteger RECEIVED = new AtomicInteger();

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KafkaScenarioSupport.KAFKA_IMAGE);

    @Configuration
    @EnableEventListeners
    @EnableTopicListeners
    @EnableDomainEventBus
    static class ListenAndSend {
    }

    @Test
    void eachClientOfTheDomainHasAClientIdOfItsOwn() throws Exception {
        publish(KAFKA, TOPIC, "{\"id\":1}");
        HandlerRegistry handlers = HandlerRegistry.register()
                .listenDomainEvent(DOMAIN, EVENT, event -> {
                    RECEIVED.incrementAndGet();
                    return Mono.empty();
                }, Map.class)
                .listenTopic(DOMAIN, TOPIC, message -> {
                    RECEIVED.incrementAndGet();
                    return Mono.empty();
                });
        Logger appInfo = (Logger) LoggerFactory.getLogger("org.apache.kafka.common.utils.AppInfoParser");
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        appInfo.addAppender(logged);

        try (AnnotationConfigApplicationContext context = startOnOwnDomain(KAFKA, "client-ids-app", DOMAIN,
                Map.of("connectionProperties.client-id", CLIENT_ID), handlers, ListenAndSend.class)) {
            Mono.from(context.getBean(DomainEventBus.class).emit(DOMAIN, new DomainEvent<>(EVENT, "1", Map.of())))
                    .block(Duration.ofSeconds(30));
            await().atMost(Duration.ofSeconds(60)).until(() -> RECEIVED.get() >= 2);

            List<String> producers = clientIds("kafka.producer");
            List<String> consumers = clientIds("kafka.consumer");
            List<String> admins = clientIds("kafka.admin.client");
            assertThat(producers).singleElement().asString().matches(CLIENT_ID + "-[0-9a-f]{6}-producer");
            assertThat(consumers).as("one consumer per type of listener").hasSize(2)
                    .allMatch(id -> id.matches(CLIENT_ID + "-[0-9a-f]{6}-consumer-\\d+"));
            assertThat(admins).singleElement().asString().matches(CLIENT_ID + "-[0-9a-f]{6}-admin");
            List<String> all = new ArrayList<>(producers);
            all.addAll(consumers);
            all.addAll(admins);
            assertThat(all).doesNotHaveDuplicates();
        } finally {
            appInfo.detachAppender(logged);
        }

        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("already exists, so skipping a new mbean creation"));
    }

    /**
     * The {@code client.id} of the live clients of a type, from their JMX application info beans.
     */
    private static List<String> clientIds(String domain) throws MalformedObjectNameException {
        return ManagementFactory.getPlatformMBeanServer()
                .queryNames(new ObjectName(domain + ":type=app-info,*"), null).stream()
                .map(name -> name.getKeyProperty(name.getKeyProperty("id") != null ? "id" : "client-id"))
                .filter(id -> id != null && id.startsWith(CLIENT_ID))
                // A client may be registered under both keys, "id" and "client-id"
                .distinct()
                .sorted()
                .toList();
    }
}
