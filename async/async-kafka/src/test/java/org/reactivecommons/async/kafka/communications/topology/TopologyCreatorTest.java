package org.reactivecommons.async.kafka.communications.topology;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.kafka.communications.exceptions.TopicNotFoundException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopologyCreatorTest {

    private TopologyCreator creator;
    @Mock
    private AdminClient adminClient;
    @Mock
    private ListTopicsResult listTopicsResult;
    @Mock
    private CreateTopicsResult createTopicsResult;
    private KafkaCustomizations customizations;

    @BeforeEach
    void setUp() {
        Map<String, String> config = new HashMap<>();
        config.put("cleanup.policy", "compact");
        TopicCustomization customization = new TopicCustomization("topic1", 3, (short) 1, config);
        customizations = KafkaCustomizations.withTopic("topic1", customization);
    }

    @Test
    void shouldCreateTopics() {
        // Arrange
        KafkaFutureImpl<Set<String>> names = new KafkaFutureImpl<>();
        names.complete(Set.of("topic1", "topic2"));
        doReturn(names).when(listTopicsResult).names();
        when(adminClient.listTopics(any(ListTopicsOptions.class))).thenReturn(listTopicsResult);

        KafkaFutureImpl<Void> create = new KafkaFutureImpl<>();
        create.complete(null);
        doReturn(create).when(createTopicsResult).all();
        when(adminClient.createTopics(any())).thenReturn(createTopicsResult);
        creator = new TopologyCreator(adminClient, customizations);
        // Act
        Mono<Void> flow = creator.createTopics(List.of("topic1", "topic2"));
        // Assert
        StepVerifier.create(flow)
                .verifyComplete();
    }

    @Test
    void shouldCheckTopics() {
        // Arrange
        KafkaFutureImpl<Set<String>> names = new KafkaFutureImpl<>();
        names.complete(Set.of("topic1", "topic2"));
        doReturn(names).when(listTopicsResult).names();
        when(adminClient.listTopics(any(ListTopicsOptions.class))).thenReturn(listTopicsResult);
        creator = new TopologyCreator(adminClient, customizations);
        // Act
        creator.checkTopic("topic1");
        // Assert
        verify(listTopicsResult, times(1)).names();
    }

    @Test
    void shouldFailWhenCheckTopics() {
        // Arrange
        KafkaFutureImpl<Set<String>> names = new KafkaFutureImpl<>();
        names.complete(Set.of("topic1", "topic2"));
        doReturn(names).when(listTopicsResult).names();
        when(adminClient.listTopics(any(ListTopicsOptions.class))).thenReturn(listTopicsResult);
        creator = new TopologyCreator(adminClient, customizations);
        // Assert
        assertThrows(TopicNotFoundException.class, () ->
                // Act
                creator.checkTopic("topic3"));
    }
}
