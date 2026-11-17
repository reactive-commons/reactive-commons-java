package org.reactivecommons.async.kafka.communications.topology;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.kafka.communications.exceptions.TopicNotFoundException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TopologyCreator#ensureTopic(String)} checks a topic with the admin client without blocking the subscribing
 * thread, and does not ask the cluster again for a topic found missing within {@link TopologyCreator#MISSING_TOPIC_TTL}.
 */
class TopologyCreatorEnsureTopicTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Duration EXISTING_TTL = Duration.ofMinutes(5);

    private final AdminClient adminClient = mock(AdminClient.class);
    private final AtomicLong now = new AtomicLong();
    private TopologyCreator creator;

    @BeforeEach
    void setUp() {
        creator = new TopologyCreator(adminClient, new KafkaCustomizations(), true, TTL, EXISTING_TTL, now::get);
    }

    /**
     * KC-12: building the creator used to list every topic of the cluster, blocking the startup of the application
     * up to 60 s when the broker could not be reached.
     */
    @Test
    void buildingTheCreatorDoesNotAskTheCluster() {
        new TopologyCreator(adminClient, new KafkaCustomizations(), true);

        verifyNoInteractions(adminClient);
    }

    @Test
    void aCreatedTopicIsNotDescribed() {
        CreateTopicsResult created = mock(CreateTopicsResult.class);
        KafkaFutureImpl<Void> done = new KafkaFutureImpl<>();
        done.complete(null);
        doReturn(done).when(created).all();
        when(adminClient.createTopics(anyCollection())).thenReturn(created);
        StepVerifier.create(creator.createTopics(List.of("known"))).expectComplete().verify(TIMEOUT);

        StepVerifier.create(creator.ensureTopic("known")).expectComplete().verify(TIMEOUT);

        verify(adminClient, never()).describeTopics(anyCollection(), any(DescribeTopicsOptions.class));
        verify(adminClient, never()).listTopics(any(ListTopicsOptions.class));
    }

    /**
     * KC-12: a topic found used to be taken as existing forever, so a topic deleted afterwards was still sent to.
     */
    @Test
    void aTopicDeletedIsReportedMissingOnceItsEntryExpires() {
        describeAnswers(found(), failed(new UnknownTopicOrPartitionException("deleted")));
        StepVerifier.create(creator.ensureTopic("deleted.later")).expectComplete().verify(TIMEOUT);

        now.addAndGet(EXISTING_TTL.toNanos() - 1);
        StepVerifier.create(creator.ensureTopic("deleted.later")).expectComplete().verify(TIMEOUT);
        verify(adminClient, times(1)).describeTopics(anyCollection(), any(DescribeTopicsOptions.class));

        now.addAndGet(1);
        StepVerifier.create(creator.ensureTopic("deleted.later"))
                .expectError(TopicNotFoundException.class)
                .verify(TIMEOUT);
        verify(adminClient, times(2)).describeTopics(anyCollection(), any(DescribeTopicsOptions.class));
    }

    @Test
    void aTopicFoundIsDescribedOnlyOnce() {
        describeAnswers(found());

        StepVerifier.create(creator.ensureTopic("created.later")).expectComplete().verify(TIMEOUT);
        StepVerifier.create(creator.ensureTopic("created.later")).expectComplete().verify(TIMEOUT);

        verify(adminClient, times(1)).describeTopics(anyCollection(), any(DescribeTopicsOptions.class));
    }

    @Test
    void aMissingTopicIsDescribedOnceWithinTheMissingTopicTtl() {
        describeAnswers(failed(new UnknownTopicOrPartitionException("unknown")), found());

        for (int i = 0; i < 3; i++) {
            StepVerifier.create(creator.ensureTopic("missing"))
                    .expectError(TopicNotFoundException.class)
                    .verify(TIMEOUT);
        }
        verify(adminClient, times(1)).describeTopics(anyCollection(), any(DescribeTopicsOptions.class));

        // Once the TTL passes, the cluster is asked again: the topic was created meanwhile
        now.addAndGet(TTL.toNanos());
        StepVerifier.create(creator.ensureTopic("missing")).expectComplete().verify(TIMEOUT);
        verify(adminClient, times(2)).describeTopics(anyCollection(), any(DescribeTopicsOptions.class));
    }

    @Test
    void concurrentChecksOfTheSameTopicShareOneRequest() {
        KafkaFutureImpl<Map<String, TopicDescription>> pending = new KafkaFutureImpl<>();
        describeAnswers(pending);

        StepVerifier.create(Flux.range(0, 10).flatMap(i -> creator.ensureTopic("slow")).then())
                .then(() -> pending.complete(Map.of()))
                .expectComplete()
                .verify(TIMEOUT);

        verify(adminClient, times(1)).describeTopics(anyCollection(), any(DescribeTopicsOptions.class));
    }

    @Test
    void anErrorThatIsNotAMissingTopicIsNotRemembered() {
        describeAnswers(failed(new TimeoutException("no answer")), found());

        StepVerifier.create(creator.ensureTopic("flaky")).expectError(TimeoutException.class).verify(TIMEOUT);
        StepVerifier.create(creator.ensureTopic("flaky")).expectComplete().verify(TIMEOUT);

        verify(adminClient, times(2)).describeTopics(anyCollection(), any(DescribeTopicsOptions.class));
    }

    @Test
    void doesNotBlockTheSubscribingThreadWhileTheClusterDoesNotAnswer() {
        describeAnswers(new KafkaFutureImpl<>());

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> creator.ensureTopic("no.answer").subscribe());
    }

    @Test
    void checksNothingWhenCheckingTopicsIsDisabled() {
        TopologyCreator unchecked = new TopologyCreator(adminClient, new KafkaCustomizations(),
                false);

        StepVerifier.create(unchecked.ensureTopic("anything")).expectComplete().verify(TIMEOUT);

        verify(adminClient, never()).describeTopics(anyCollection(), any(DescribeTopicsOptions.class));
    }

    @SafeVarargs
    private void describeAnswers(KafkaFutureImpl<Map<String, TopicDescription>>... futures) {
        List<DescribeTopicsResult> results = Stream.of(futures).map(future -> {
            DescribeTopicsResult result = mock(DescribeTopicsResult.class);
            doReturn(future).when(result).allTopicNames();
            return result;
        }).toList();
        var stubbing = when(adminClient.describeTopics(anyCollection(), any(DescribeTopicsOptions.class)));
        for (DescribeTopicsResult result : results) {
            stubbing = stubbing.thenReturn(result);
        }
    }

    private static KafkaFutureImpl<Map<String, TopicDescription>> found() {
        KafkaFutureImpl<Map<String, TopicDescription>> future = new KafkaFutureImpl<>();
        future.complete(Map.of());
        return future;
    }

    private static KafkaFutureImpl<Map<String, TopicDescription>> failed(Throwable error) {
        KafkaFutureImpl<Map<String, TopicDescription>> future = new KafkaFutureImpl<>();
        future.completeExceptionally(error);
        return future;
    }
}
