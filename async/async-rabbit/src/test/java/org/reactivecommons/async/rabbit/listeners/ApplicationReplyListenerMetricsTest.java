package org.reactivecommons.async.rabbit.listeners;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.rabbitmq.Receiver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ApplicationReplyListenerMetricsTest {

    @Test
    void theLostRepliesCounterExistsAtZeroBeforeAnyReplyIsLost() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReactiveMessageListener listener = new ReactiveMessageListener(mock(Receiver.class),
                mock(TopologyCreator.class), 250, 250, new SubscriberRegistry(), registry, "app", null);

        new ApplicationReplyListener(new ReactiveReplyRouter(), listener, "reply-queue", "globalReply", true);

        Counter lost = registry.find("reactive_commons.consumer.replies.lost").tag("domain", "app").counter();
        assertThat(lost).as("registered when the reply listener is created").isNotNull();
        assertThat(lost.count()).isZero();
    }

    @Test
    void theReplyListenerOnlyReportsItsLivenessAndRestarts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReactiveMessageListener listener = new ReactiveMessageListener(mock(Receiver.class),
                mock(TopologyCreator.class), 250, 250, new SubscriberRegistry(), registry, "app", null);

        new ApplicationReplyListener(new ReactiveReplyRouter(), listener, "reply-queue", "globalReply", true);

        assertThat(registry.find("reactive_commons.consumer.active").tag("queue", "reply-queue").gauge()).isNotNull();
        assertThat(registry.find("reactive_commons.consumer.restart").tag("queue", "reply-queue").counters())
                .hasSize(3);
        // its replies are routed to the waiting callers, never handled: these would stay at 0 forever
        for (String name : List.of("reactive_commons.consumer.messages", "reactive_commons.consumer.in_flight",
                "reactive_commons.consumer.handler.latency", "reactive_commons.consumer.message.size",
                "reactive_commons.consumer.retry.attempts")) {
            assertThat(registry.find(name).tag("queue", "reply-queue").meters()).as(name).isEmpty();
        }
    }
}
