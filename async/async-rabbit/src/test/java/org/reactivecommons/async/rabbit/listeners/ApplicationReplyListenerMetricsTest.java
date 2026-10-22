package org.reactivecommons.async.rabbit.listeners;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.rabbitmq.Receiver;

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
}
