package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.AMQP;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Sender;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void shouldDeclareExchange() {
        ExchangeSpecification exchange = ExchangeSpecification.exchange("test.exchange");
        AMQP.Exchange.DeclareOk declareOk = mock(AMQP.Exchange.DeclareOk.class);
        when(sender.declare(exchange)).thenReturn(Mono.just(declareOk));

        StepVerifier.create(creator.declare(exchange))
                .expectNext(declareOk)
                .verifyComplete();

        verify(sender).declare(exchange);
    }

    @Test
    void shouldWrapExceptionWhenDeclareExchangeFails() {
        ExchangeSpecification exchange = ExchangeSpecification.exchange("test.exchange");
        RuntimeException error = new RuntimeException("Connection error");
        when(sender.declare(exchange)).thenReturn(Mono.error(error));

        StepVerifier.create(creator.declare(exchange))
                .expectErrorMatches(e -> e instanceof TopologyCreator.TopologyDefException
                        && e.getCause() == error)
                .verify();
    }

    @Test
    void shouldDeclareQueue() {
        QueueSpecification queue = QueueSpecification.queue("test.queue");
        AMQP.Queue.DeclareOk declareOk = mock(AMQP.Queue.DeclareOk.class);
        when(sender.declare(any(QueueSpecification.class))).thenReturn(Mono.just(declareOk));

        StepVerifier.create(creator.declare(queue))
                .expectNext(declareOk)
                .verifyComplete();

        verify(sender).declare(any(QueueSpecification.class));
    }

    @Test
    void shouldWrapExceptionWhenDeclareQueueFails() {
        QueueSpecification queue = QueueSpecification.queue("test.queue");
        RuntimeException error = new RuntimeException("Connection error");
        when(sender.declare(any(QueueSpecification.class))).thenReturn(Mono.error(error));

        StepVerifier.create(creator.declare(queue))
                .expectErrorMatches(e -> e instanceof TopologyCreator.TopologyDefException
                        && e.getCause() == error)
                .verify();
    }

    @Test
    void shouldBindQueue() {
        var binding = BindingSpecification.binding("exchange", "routingKey", "queue");
        AMQP.Queue.BindOk bindOk = mock(AMQP.Queue.BindOk.class);
        when(sender.bind(binding)).thenReturn(Mono.just(bindOk));

        StepVerifier.create(creator.bind(binding))
                .expectNext(bindOk)
                .verifyComplete();

        verify(sender).bind(binding);
    }

    @Test
    void shouldWrapExceptionWhenBindFails() {
        var binding = BindingSpecification.binding("exchange", "routingKey", "queue");
        RuntimeException error = new RuntimeException("Connection error");
        when(sender.bind(binding)).thenReturn(Mono.error(error));

        StepVerifier.create(creator.bind(binding))
                .expectErrorMatches(e -> e instanceof TopologyCreator.TopologyDefException
                        && e.getCause() == error)
                .verify();
    }

    @Test
    void shouldUnbindQueue() {
        var binding = BindingSpecification.binding("exchange", "routingKey", "queue");
        AMQP.Queue.UnbindOk unbindOk = mock(AMQP.Queue.UnbindOk.class);
        when(sender.unbind(binding)).thenReturn(Mono.just(unbindOk));

        StepVerifier.create(creator.unbind(binding))
                .expectNext(unbindOk)
                .verifyComplete();

        verify(sender).unbind(binding);
    }

    @Test
    void shouldWrapExceptionWhenUnbindFails() {
        var binding = BindingSpecification.binding("exchange", "routingKey", "queue");
        RuntimeException error = new RuntimeException("Connection error");
        when(sender.unbind(binding)).thenReturn(Mono.error(error));

        StepVerifier.create(creator.unbind(binding))
                .expectErrorMatches(e -> e instanceof TopologyCreator.TopologyDefException
                        && e.getCause() == error)
                .verify();
    }

    @Test
    void shouldDeclareDLQWithRequiredArguments() {
        AMQP.Queue.DeclareOk declareOk = mock(AMQP.Queue.DeclareOk.class);
        when(sender.declare(any(QueueSpecification.class))).thenReturn(Mono.just(declareOk));

        StepVerifier.create(creator.declareDLQ(
                        "test.queue", "retry.exchange", 5000, Optional.empty()
                ))
                .expectNext(declareOk)
                .verifyComplete();
    }

    @Test
    void shouldDeclareDLQWithMaxLengthBytes() {
        AMQP.Queue.DeclareOk declareOk = mock(AMQP.Queue.DeclareOk.class);
        when(sender.declare(any(QueueSpecification.class))).thenReturn(Mono.just(declareOk));

        StepVerifier.create(creator.declareDLQ(
                        "test.queue", "retry.exchange", 5000, Optional.of(1000000)
                ))
                .expectNext(declareOk)
                .verifyComplete();
    }

    @Test
    void shouldDeclareQueueWithDLQExchange() {
        AMQP.Queue.DeclareOk declareOk = mock(AMQP.Queue.DeclareOk.class);
        when(sender.declare(any(QueueSpecification.class))).thenReturn(Mono.just(declareOk));

        StepVerifier.create(creator.declareQueue(
                        "test.queue", "dlq.exchange", Optional.empty()
                ))
                .expectNext(declareOk)
                .verifyComplete();
    }

    @Test
    void shouldDeclareQueueWithDLQExchangeAndMaxLengthBytes() {
        AMQP.Queue.DeclareOk declareOk = mock(AMQP.Queue.DeclareOk.class);
        when(sender.declare(any(QueueSpecification.class))).thenReturn(Mono.just(declareOk));

        StepVerifier.create(creator.declareQueue(
                        "test.queue", "dlq.exchange", Optional.of(500000)
                ))
                .expectNext(declareOk)
                .verifyComplete();
    }

    @Test
    void shouldDeclareQueueWithDLQExchangeAndRoutingKey() {
        AMQP.Queue.DeclareOk declareOk = mock(AMQP.Queue.DeclareOk.class);
        when(sender.declare(any(QueueSpecification.class))).thenReturn(Mono.just(declareOk));

        StepVerifier.create(creator.declareQueue(
                        "test.queue", "dlq.exchange", Optional.empty(),
                        Optional.of("dlq.routing.key")
                ))
                .expectNext(declareOk)
                .verifyComplete();
    }

    @Test
    void shouldDeclareQueueWithAllDLQOptions() {
        AMQP.Queue.DeclareOk declareOk = mock(AMQP.Queue.DeclareOk.class);
        when(sender.declare(any(QueueSpecification.class))).thenReturn(Mono.just(declareOk));

        StepVerifier.create(creator.declareQueue(
                        "test.queue", "dlq.exchange", Optional.of(500000),
                        Optional.of("dlq.routing.key")
                ))
                .expectNext(declareOk)
                .verifyComplete();
    }

    @Test
    void shouldDeclareSimpleQueue() {
        AMQP.Queue.DeclareOk declareOk = mock(AMQP.Queue.DeclareOk.class);
        when(sender.declare(any(QueueSpecification.class))).thenReturn(Mono.just(declareOk));

        StepVerifier.create(creator.declareQueue("test.queue", Optional.empty()))
                .expectNext(declareOk)
                .verifyComplete();
    }

    @Test
    void shouldDeclareSimpleQueueWithMaxLengthBytes() {
        AMQP.Queue.DeclareOk declareOk = mock(AMQP.Queue.DeclareOk.class);
        when(sender.declare(any(QueueSpecification.class))).thenReturn(Mono.just(declareOk));

        StepVerifier.create(creator.declareQueue("test.queue", Optional.of(1000000)))
                .expectNext(declareOk)
                .verifyComplete();
    }

    @Test
    void shouldPreserveExistingArgumentsWhenFillingQueueType() {
        Map<String, Object> existingArgs = new HashMap<>();
        existingArgs.put("x-custom-arg", "custom-value");
        QueueSpecification spec = QueueSpecification.queue("test").arguments(existingArgs);

        QueueSpecification result = creator.fillQueueType(spec);

        assertThat(result.getArguments())
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-custom-arg", "custom-value");
    }

    @Test
    void shouldNotInjectQueueTypeWhenNull() {
        TopologyCreator creatorWithNullType = new TopologyCreator(sender, null);
        QueueSpecification spec = QueueSpecification.queue("test");

        QueueSpecification result = creatorWithNullType.fillQueueType(spec);

        assertThat(result.getArguments()).doesNotContainKey("x-queue-type");
    }

    @Test
    void shouldHandleQueueSpecificationWithNullArguments() {
        QueueSpecification spec = QueueSpecification.queue("test");

        QueueSpecification result = creator.fillQueueType(spec);

        assertThat(result.getArguments()).containsEntry("x-queue-type", "quorum");
    }

    @Test
    void shouldForceClassicForBothAutodeleteAndExclusive() {
        QueueSpecification spec = QueueSpecification.queue("temp")
                .autoDelete(true)
                .exclusive(true);

        QueueSpecification result = creator.fillQueueType(spec);

        assertThat(result.getArguments()).containsEntry("x-queue-type", "classic");
    }
}
