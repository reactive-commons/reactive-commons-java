package org.reactivecommons.async.rabbit.communications;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.SendConfirmUnknownException;
import org.reactivecommons.async.commons.exceptions.SendFailureNoAckException;
import org.reactivecommons.async.commons.exceptions.SendFailureReturnedException;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.RabbitFluxException;
import reactor.rabbitmq.SendOptions;
import reactor.rabbitmq.Sender;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
class ReactiveMessageSenderTest {

    private ReactiveMessageSender messageSender;

    private final JsonMapper jsonMapper = new JsonMapper();

    @Mock
    private Sender sender;

    @Spy
    private final MessageConverter messageConverter = new RabbitJacksonMessageConverter(jsonMapper);

    @Mock
    private final SendOptions sendOptions = new SendOptions();

    @Mock
    private UnroutableMessageNotifier unroutableMessageNotifier;

    @BeforeEach
    void init() {
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            final Flux<OutboundMessage> argument = invocation.getArgument(0);
            return argument
                    .map(outboundMessage -> new OutboundMessageResult<>(outboundMessage, true));
        });
        lenient().when(sender.send(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            final Flux<OutboundMessage> argument = invocation.getArgument(0);
            argument.subscribe(); // Subscribe to initialize the sink
            return Mono.never();
        });
        String sourceApplication = "TestApp";

        messageSender = new ReactiveMessageSender(sender, sourceApplication, messageConverter, null, false,
                unroutableMessageNotifier);
    }

    @Test
    void shouldCallUnroutableMessageHandlerWhenMessageIsReturned() {
        String sourceApplication = "TestApp";
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            Flux<OutboundMessage> argument = invocation.getArgument(0);
            return argument.map(msg -> new OutboundMessageResult<>(msg, false, true));
        });

        messageSender = new ReactiveMessageSender(
                sender, sourceApplication, messageConverter, null, true, unroutableMessageNotifier
        );
        SomeClass messageContent = new SomeClass("id", "name", new Date());

        // the mock reports the returned message as not confirmed: the send fails, only the notification matters here
        messageSender.sendWithConfirm(messageContent, "exchange", "rkey", new HashMap<>(), true)
                .subscribe(v -> {
                }, e -> {
                });

        verify(unroutableMessageNotifier, timeout(1000).times(1))
                .notifyUnroutableMessage(any(OutboundMessageResult.class));
    }

    @Test
    void shouldSendBatchWithConfirmSuccessfully() {
        Flux<SomeClass> messages = Flux.just(
                new SomeClass("id1", "name1", new Date()),
                new SomeClass("id2", "name2", new Date())
        );
        String exchange = "test.exchange";
        String routingKey = "test.routingKey";
        Map<String, Object> headers = new HashMap<>();

        OutboundMessageResult<OutboundMessage> result1 = new OutboundMessageResult<>(null, true);
        OutboundMessageResult<OutboundMessage> result2 = new OutboundMessageResult<>(null, true);

        when(sender.sendWithPublishConfirms(any(Publisher.class), any(SendOptions.class)))
                .thenReturn(Flux.just(result1, result2));

        Flux<OutboundMessageResult> resultFlux = messageSender.sendWithConfirmBatch(messages, exchange, routingKey,
                headers, true);

        StepVerifier.create(resultFlux).verifyComplete();
    }

    @Test
    void sendWithConfirmEmptyNullMessage() {
        final Mono<Void> voidMono = messageSender.sendWithConfirm(null, "exchange", "rkey", new HashMap<>(), true);

        StepVerifier.create(voidMono).verifyComplete();
    }

    @Test
    void sendWithConfirmSomeMessage() {
        SomeClass some = new SomeClass("42", "Daniel", new Date());
        final Mono<Void> voidMono = messageSender.sendWithConfirm(some, "exchange", "rkey", new HashMap<>(), true);

        StepVerifier.create(voidMono).verifyComplete();
    }

    @Test
    void theLatencyAndTheSizeOfAPublishedMessageAreMeasured() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReactiveMessageSender meteredSender = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                false, unroutableMessageNotifier, Duration.ofSeconds(5), 13, 1024, new ConnectionBlockedState(),
                registry, "test-domain");
        try {
            StepVerifier.create(meteredSender.sendWithConfirm(new SomeClass("42", "Daniel", new Date()), "exchange",
                            "rkey", new HashMap<>(), true))
                    .verifyComplete();

            Timer latency = registry.find("reactive_commons.publish.latency")
                    .tags("domain", "test-domain", "confirm", "true", "outcome", "ack").timer();
            assertThat(latency).as("latency timer tagged with the ack outcome").isNotNull();
            assertThat(latency.count()).isEqualTo(1);

            DistributionSummary size = registry.find("reactive_commons.publish.message.size")
                    .tags("domain", "test-domain", "confirm", "true").summary();
            assertThat(size).as("message size summary").isNotNull();
            assertThat(size.count()).isEqualTo(1);
            assertThat(size.totalAmount()).isPositive();
        } finally {
            meteredSender.close();
        }
    }

    /**
     * A negative confirmation for a message that was never written to a channel (here the mocked sender never
     * writes it) is a local failure, not a broker {@code basic.nack}: both the outcome and the latency say so.
     */
    @Test
    void aMessageNeverWrittenToTheChannelIsCountedAsFailedNotAsNacked() {
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            Flux<OutboundMessage> argument = invocation.getArgument(0);
            return argument.map(msg -> new OutboundMessageResult<>(msg, false, false));
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReactiveMessageSender meteredSender = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                false, unroutableMessageNotifier, Duration.ofSeconds(5), 13, 1024, new ConnectionBlockedState(),
                registry, "test-domain");
        try {
            StepVerifier.create(meteredSender.sendWithConfirm(new SomeClass("42", "Daniel", new Date()), "exchange",
                            "rkey", new HashMap<>(), true))
                    .expectErrorSatisfies(error -> assertThat(error)
                            .isExactlyInstanceOf(SendFailureNoAckException.class)
                            .hasMessageStartingWith("Message not published"))
                    .verify();

            assertThat(registry.find("reactive_commons.publish.outcome")
                    .tags("domain", "test-domain", "confirm", "true", "outcome", "failed").counter().count())
                    .isEqualTo(1);
            assertThat(registry.find("reactive_commons.publish.latency")
                    .tags("domain", "test-domain", "confirm", "true", "outcome", "failed").timer().count())
                    .isEqualTo(1);
            assertThat(registry.find("reactive_commons.publish.outcome").tag("outcome", "nack").counter())
                    .as("the broker never nacked it").isNull();
            assertThat(registry.find("reactive_commons.publish.latency").tag("outcome", "nack").timer()).isNull();
        } finally {
            meteredSender.close();
        }
    }

    @Test
    void sendWithConfirmFailsWhenTheBrokerReturnsTheMessage() {
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            Flux<OutboundMessage> argument = invocation.getArgument(0);
            return argument.map(msg -> new OutboundMessageResult<>(msg, true, true));
        });
        // the flows are subscribed when the sender is created: use a new sender so it sees this stub
        ReactiveMessageSender returningSender = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                false, unroutableMessageNotifier);
        try {
            StepVerifier.create(returningSender.sendWithConfirm(new SomeClass("42", "Daniel", new Date()), "exchange",
                            "rkey", new HashMap<>(), true))
                    .expectError(SendFailureReturnedException.class)
                    .verify(Duration.ofSeconds(5));
        } finally {
            returningSender.close();
        }
    }

    @Test
    void sendWithConfirmBatchFailsWhenTheBrokerReturnsTheMessage() {
        OutboundMessageResult<OutboundMessage> returned = new OutboundMessageResult<>(null, true, true);
        when(sender.sendWithPublishConfirms(any(Publisher.class), any(SendOptions.class)))
                .thenReturn(Flux.just(returned));

        StepVerifier.create(messageSender.sendWithConfirmBatch(Flux.just(new SomeClass("1", "a", new Date())),
                        "exchange", "rkey", new HashMap<>(), true))
                .expectError(SendFailureReturnedException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void sendWithConfirmBatchFailsWithUnknownOutcomeWhenNoConfirmationArrives() {
        when(sender.sendWithPublishConfirms(any(Publisher.class), any(SendOptions.class))).thenReturn(Flux.never());
        ReactiveMessageSender shortTimeoutSender = new ReactiveMessageSender(sender, "TestApp", messageConverter,
                null, false, unroutableMessageNotifier, Duration.ofMillis(100));
        try {
            StepVerifier.create(shortTimeoutSender.sendWithConfirmBatch(Flux.just(new SomeClass("1", "a", new Date())),
                            "exchange", "rkey", new HashMap<>(), true))
                    .expectError(SendConfirmUnknownException.class)
                    .verify(Duration.ofSeconds(5));
        } finally {
            shortTimeoutSender.close();
        }
    }

    @Test
    void closeFailsSendsWaitingForAnOutcome() {
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            ((Flux<OutboundMessage>) invocation.getArgument(0)).subscribe(); // consume, never confirm
            return Flux.never();
        });
        ReactiveMessageSender senderToClose = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                false, unroutableMessageNotifier);

        StepVerifier.create(senderToClose.sendWithConfirm(new SomeClass("42", "Daniel", new Date()), "exchange",
                        "rkey", new HashMap<>(), true))
                .then(senderToClose::close)
                .expectError(SendFailureNoAckException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void sendAfterCloseFailsFast() {
        messageSender.close();

        StepVerifier.create(messageSender.sendWithConfirm(new SomeClass("42", "Daniel", new Date()), "exchange",
                        "rkey", new HashMap<>(), true))
                .expectError(SendFailureNoAckException.class)
                .verify(Duration.ofSeconds(2)); // much shorter than the 10 s send timeout
    }

    @Test
    void batchAfterCloseFailsFast() {
        messageSender.close();

        StepVerifier.create(messageSender.sendWithConfirmBatch(Flux.just(new SomeClass("1", "a", new Date())),
                        "exchange", "rkey", new HashMap<>(), true))
                .expectError(SendFailureNoAckException.class)
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void reportsHealthyPublisherStatusWhileTheFlowsAreLive() {
        PublisherStatus status = messageSender.publisherStatus();

        assertThat(status.confirmFlowsLive()).isTrue();
        assertThat(status.consecutiveConfirmFailures()).isZero();
        assertThat(status.lastConfirmFailure()).isNull();
        assertThat(status.confirmFlowsDownSince()).isNull();
    }

    @Test
    void logsOnlyTheFirstResubscribeOfAFlowAtWarning() throws InterruptedException {
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class)))
                .thenReturn(Flux.error(new RabbitFluxException("boom")));
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(ReactiveMessageSender.class.getName());
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        ReactiveMessageSender failingSender = null;
        try {
            failingSender = new ReactiveMessageSender(sender, "TestApp", messageConverter, null, false,
                    unroutableMessageNotifier);
            await(() -> count(records, "publisher-confirms flow #0", Level.WARNING) >= 1
                    && count(records, "publisher-confirms flow #0", Level.FINE) >= 1);

            assertThat(count(records, "publisher-confirms flow #0", Level.WARNING)).isEqualTo(1);
            assertThat(count(records, "publisher-confirms flow #0", Level.FINE)).isGreaterThanOrEqualTo(1);
        } finally {
            if (failingSender != null) {
                failingSender.close();
            }
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }
    }

    private static long count(List<LogRecord> records, String flowName, Level level) {
        return records.stream()
                .filter(record -> level.equals(record.getLevel()))
                .filter(record -> record.getMessage() != null && record.getMessage().contains(flowName))
                .count();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within 5 seconds");
            }
            Thread.sleep(50);
        }
    }

    private record SomeClass(String id, String name, Date date) {
    }

}