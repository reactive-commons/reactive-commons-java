package org.reactivecommons.async.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.NewPartitions;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.config.annotations.EnableNotificationListener;
import org.reactivecommons.async.kafka.KafkaScenarioSupport.OwnDomain;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.createTopic;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.publish;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.startOnOwnDomain;

/**
 * Every instance of an application receives every notification, against a REAL Kafka broker: each one reads every
 * partition of the notifications by itself, without a consumer group, so none is left in the broker.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class NotificationsKafkaContainerTest {

    private static final String DOMAIN = "accounts";
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final Duration PUBLISH_EVERY = Duration.ofMillis(500);

    @Container
    private static final KafkaContainer KAFKA = KafkaScenarioSupport.kafkaContainer();

    @Configuration
    @EnableNotificationListener
    static class ListenNotifications {
    }

    @Test
    void noConsumerGroupIsLeftByTheNotificationsOfThreeStarts() throws Exception {
        String name = "notifications.restarts";
        createTopic(KAFKA, name, 1);

        for (int start = 1; start <= 3; start++) {
            List<String> received = new CopyOnWriteArrayList<>();
            try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA,
                    new OwnDomain("notifications-restarts", DOMAIN), recording(name, received),
                    ListenNotifications.class)) {
                String id = "start-" + start;
                // Read from the end of the partitions once assigned: published until it is read
                await().atMost(WAIT).pollInterval(PUBLISH_EVERY).until(() -> {
                    publish(KAFKA, name, notification(name, id));
                    return received.contains(id);
                });
            }
        }

        assertThat(groups()).as("consumer groups in the broker").noneMatch(group -> group.contains("-notification"));
    }

    @Test
    void everyInstanceReceivesTheSameNotification() throws Exception {
        String name = "notifications.instances";
        createTopic(KAFKA, name, 2);
        List<String> first = new CopyOnWriteArrayList<>();
        List<String> second = new CopyOnWriteArrayList<>();
        AtomicInteger warmUps = new AtomicInteger();

        try (AnnotationConfigApplicationContext ignoredFirst = startOnOwnDomain(KAFKA,
                new OwnDomain("notifications-instances", DOMAIN), recording(name, first), ListenNotifications.class);
             AnnotationConfigApplicationContext ignoredSecond = startOnOwnDomain(KAFKA,
                     new OwnDomain("notifications-instances", DOMAIN), recording(name, second),
                     ListenNotifications.class)) {
            // Both instances read every partition once their notifications are read
            await().atMost(WAIT).pollInterval(PUBLISH_EVERY).until(() -> {
                publish(KAFKA, name, notification(name, "warm-up-" + warmUps.incrementAndGet()));
                return first.stream().anyMatch(id -> id.startsWith("warm-up"))
                        && second.stream().anyMatch(id -> id.startsWith("warm-up"));
            });

            publish(KAFKA, name, notification(name, "the-same"));

            await().atMost(WAIT).until(() -> first.contains("the-same") && second.contains("the-same"));
        }
    }

    /**
     * A partition added to the topic of a notification is found while consuming, and read from its beginning: what
     * was published there since it was added is not skipped.
     */
    @Test
    void aPartitionAddedWhileConsumingIsReadFromItsBeginning() throws Exception {
        String name = "notifications.partitions";
        createTopic(KAFKA, name, 1);
        List<String> received = new CopyOnWriteArrayList<>();

        try (AnnotationConfigApplicationContext ignored = startOnOwnDomain(KAFKA,
                new OwnDomain("notifications-partitions", DOMAIN), recording(name, received),
                ListenNotifications.class)) {
            await().atMost(WAIT).pollInterval(PUBLISH_EVERY).until(() -> {
                publish(KAFKA, name, 0, notification(name, "warm-up"));
                return received.contains("warm-up");
            });

            try (AdminClient admin = AdminClient.create(
                    Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
                admin.createPartitions(Map.of(name, NewPartitions.increaseTo(2))).all().get();
            }
            publish(KAFKA, name, 1, notification(name, "in-the-new-partition"));

            await().atMost(Duration.ofSeconds(90)).until(() -> received.contains("in-the-new-partition"));
        }
    }

    private static HandlerRegistry recording(String name, List<String> received) {
        return HandlerRegistry.register().listenNotificationEvent(DOMAIN, name,
                notification -> Mono.fromRunnable(() -> received.add(notification.getEventId())), Map.class);
    }

    private static String notification(String name, String id) {
        return "{\"name\":\"" + name + "\",\"eventId\":\"" + id + "\",\"data\":{\"amount\":1}}";
    }

    private static List<String> groups() throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            return admin.listGroups().all().get().stream().map(GroupListing::groupId).toList();
        }
    }
}
