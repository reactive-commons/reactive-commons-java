package org.reactivecommons.async.rabbit.communications;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Sender;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TopologyCreatorTest {
    @Mock
    private Sender sender;

    private TopologyCreator creator;

    @BeforeEach
    void setUp() {
        creator = new TopologyCreator(sender, "quorum");
    }

    @Test
    void shouldInjectQueueType() {
        QueueSpecification spec = creator.fillQueueType(QueueSpecification.queue("durable"));
        assertThat(spec.getArguments()).containsEntry("x-queue-type", "quorum");
    }

    @Test
    void shouldForceClassicQueueTypeWhenAutodelete() {
        QueueSpecification spec = creator.fillQueueType(QueueSpecification.queue("autodelete").autoDelete(true));
        assertThat(spec.getArguments()).containsEntry("x-queue-type", "classic");
    }

    @Test
    void shouldForceClassicQueueTypeWhenExclusive() {
        QueueSpecification spec = creator.fillQueueType(QueueSpecification.queue("exclusive").exclusive(true));
        assertThat(spec.getArguments()).containsEntry("x-queue-type", "classic");
    }
}
