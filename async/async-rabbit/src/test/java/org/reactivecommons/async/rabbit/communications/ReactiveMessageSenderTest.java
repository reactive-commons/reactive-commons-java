package org.reactivecommons.async.rabbit.communications;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.rabbitmq.client.AMQP;
import io.micrometer.core.instrument.Counter;
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
import org.reactivecommons.async.commons.exceptions.SendFailureMessageTooLargeException;
import org.reactivecommons.async.commons.exceptions.SendFailureNoAckException;
import org.reactivecommons.async.commons.exceptions.SendFailureReturnedException;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.core.publisher.Sinks;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.RabbitFluxException;
import reactor.rabbitmq.SendOptions;
import reactor.rabbitmq.Sender;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
            // the broker answers after the verify below started waiting, as on a slow runner: the notifier must not
            // hold a lock the verification waits on (a synchronized method of a mock does)
            return argument.delayElements(Duration.ofMillis(300))
                    .map(msg -> new OutboundMessageResult<>(msg, false, true));
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

        verify(unroutableMessageNotifier, timeout(5_000).times(1))
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

    /**
     * While no publishing flow is available (e.g. the connection is being recovered), the messages wait for one to be
     * registered instead of being handed over again and again until it is.
     */
    @Test
    void messagesWaitingForAPublishingFlowAreHandedOverOnceItIsRegistered() throws Exception {
        CompletableFuture<Void> flowsReady = new CompletableFuture<>();
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            final Flux<OutboundMessage> argument = invocation.getArgument(0);
            // the flows get their channel (and register) only once flowsReady completes
            return Mono.fromFuture(flowsReady).thenMany(argument
                    .map(outboundMessage -> new OutboundMessageResult<>(outboundMessage, true)));
        });
        int messages = 200;
        try (ReactiveMessageSender waitingSender = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                false, unroutableMessageNotifier, Duration.ofSeconds(10))) {
            List<CompletableFuture<Void>> sends = new ArrayList<>();
            for (int i = 0; i < messages; i++) {
                sends.add(waitingSender.sendWithConfirm(new SomeClass(String.valueOf(i), "a", new Date()), "exchange",
                        "rkey", new HashMap<>(), true).toFuture());
            }
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))
                    .until(() -> handOvers(waitingSender) <= messages);
            long handOversWhileWaiting = handOvers(waitingSender);

            long registeredAt = System.nanoTime();
            flowsReady.complete(null);
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                    .get(5, TimeUnit.SECONDS);
            long completedAfterMs = Duration.ofNanos(System.nanoTime() - registeredAt).toMillis();

            System.out.printf("hand-overs of %d messages waiting 1 s for a flow: %d, completed %d ms after the flows "
                    + "registered%n", messages, handOversWhileWaiting, completedAfterMs);
            assertThat(handOversWhileWaiting).as("hand-overs while no flow is available").isLessThanOrEqualTo(messages);
            assertThat(completedAfterMs).as("time to publish once a flow is registered").isLessThan(2_000);
        }
    }

    /**
     * The flows the message is handed to die before writing it, one after another, until a flow keeps working: the
     * message is retried on each new flow and written once.
     */
    @Test
    void aMessageRetriedOnManyFlowsInARowIsPublishedOnce() {
        int dyingFlows = 12; // each of the 4 publisher-confirms flows dies three times before one keeps working
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicInteger written = new AtomicInteger();
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            final Flux<OutboundMessage> argument = invocation.getArgument(0);
            if (subscriptions.getAndIncrement() >= dyingFlows) {
                return argument.map(message -> {
                    written.incrementAndGet();
                    return new OutboundMessageResult<>(message, true);
                });
            }
            // like a flow whose channel closed: the message reaching it is discarded unwritten and the flow ends
            return argument.concatMap(message -> Mono.deferContextual(context -> {
                Operators.onDiscard(message, Context.of(context));
                return Mono.error(new IllegalStateException("channel closed"));
            }));
        });
        try (ReactiveMessageSender retryingSender = new ReactiveMessageSender(sender, "TestApp", messageConverter,
                null, false, unroutableMessageNotifier, Duration.ofSeconds(20))) {
            StepVerifier.create(retryingSender.sendWithConfirm(new SomeClass("42", "Daniel", new Date()),
                            "exchange", "rkey", new HashMap<>(), true))
                    .verifyComplete();

            assertThat(subscriptions.get()).as("flows the message was handed to").isGreaterThan(dyingFlows);
            assertThat(written.get()).as("times the message was written").isEqualTo(1);
        }
    }

    /**
     * The flows the message is handed to die before writing it (e.g. their channels close), and no flow gets a channel
     * again before the send timeout: the message was never published, so its outcome is not in doubt.
     */
    @Test
    void aMessageWhoseFlowsDiedBeforeWritingItIsReportedAsNotPublished() {
        AtomicInteger subscriptions = new AtomicInteger();
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            final Flux<OutboundMessage> argument = invocation.getArgument(0);
            if (subscriptions.getAndIncrement() >= 4) {
                return Flux.never(); // re-subscribed flows never get a channel again
            }
            // like a flow whose channel closed: the message reaching it is discarded unwritten and the flow ends
            return argument.concatMap(message -> Mono.deferContextual(context -> {
                Operators.onDiscard(message, Context.of(context));
                return Mono.error(new IllegalStateException("channel closed"));
            }));
        });
        try (ReactiveMessageSender dyingFlowsSender = new ReactiveMessageSender(sender, "TestApp", messageConverter,
                null, false, unroutableMessageNotifier, Duration.ofSeconds(1))) {
            StepVerifier.create(dyingFlowsSender.sendWithConfirm(new SomeClass("42", "Daniel", new Date()),
                            "exchange", "rkey", new HashMap<>(), true))
                    .expectErrorSatisfies(error -> assertThat(error)
                            .isInstanceOf(SendFailureNoAckException.class)
                            .hasMessageContaining("not published"))
                    .verify(Duration.ofSeconds(5));
        }
    }

    /**
     * A publish without confirms that blocks (e.g. the broker blocks the connection) completes after the sender shut
     * its completion pool down: the published message must not be reported as a failed publish.
     */
    @Test
    void aMessagePublishedAfterTheSenderClosedItsPoolsIsNotAFailedPublish() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch poolsShutDown = new CountDownLatch(1);
        CountDownLatch publishedCalled = new CountDownLatch(1);
        AtomicReference<Throwable> publishedFailure = new AtomicReference<>();
        when(sender.send(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            final Flux<OutboundMessage> argument = invocation.getArgument(0);
            argument.subscribe(message -> {
                publishing.countDown();
                awaitUninterruptibly(poolsShutDown); // the basicPublish blocks until the sender closed its pools
                try {
                    // protected: called by the Sender once basicPublish returned
                    Method published = OutboundMessage.class.getDeclaredMethod("published");
                    published.setAccessible(true);
                    published.invoke(message);
                } catch (InvocationTargetException e) {
                    publishedFailure.set(e.getCause());
                } catch (ReflectiveOperationException e) {
                    publishedFailure.set(e);
                } finally {
                    publishedCalled.countDown();
                }
            });
            return Mono.never();
        });
        ReactiveMessageSender closingSender = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                false, unroutableMessageNotifier);
        closingSender.sendNoConfirm(new SomeClass("42", "Daniel", new Date()), "exchange", "rkey", new HashMap<>(),
                false).subscribe(null, error -> {
        });
        assertThat(publishing.await(5, TimeUnit.SECONDS)).as("publishing").isTrue();

        Thread closing = new Thread(closingSender::close);
        closing.start();
        java.lang.reflect.Field field = ReactiveMessageSender.class.getDeclaredField("executorService");
        field.setAccessible(true);
        ExecutorService completionPool =
                (ExecutorService) field.get(closingSender);
        try {
            await().atMost(Duration.ofSeconds(15)).until(completionPool::isShutdown);
        } finally {
            poolsShutDown.countDown();
        }
        closing.join(15_000);
        assertThat(publishedCalled.await(5, TimeUnit.SECONDS)).as("publish completed").isTrue();

        assertThat(completionPool.isShutdown()).as("completion pool shut down before the publish completed").isTrue();
        assertThat(publishedFailure.get()).as("failure reported for a message that was published").isNull();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static long handOvers(ReactiveMessageSender sender) throws Exception {
        java.lang.reflect.Field field = ReactiveMessageSender.class.getDeclaredField("executorService2");
        field.setAccessible(true);
        return ((ThreadPoolExecutor) field.get(sender)).getCompletedTaskCount();
    }

    /**
     * A sender that is never closed (e.g. used outside Spring) must not keep the JVM alive, nor keep idle threads
     * forever.
     */
    @Test
    void thePoolsOfTheSenderUseDaemonThreadsReleasedWhenIdle() throws Exception {
        StepVerifier.create(messageSender.sendWithConfirm(new SomeClass("42", "Daniel", new Date()), "exchange",
                "rkey", new HashMap<>(), true)).verifyComplete();

        List<Thread> poolThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("RMessageSender"))
                .toList();
        assertThat(poolThreads).as("threads of the hand-over and completion pools").isNotEmpty()
                .allSatisfy(thread -> assertThat(thread.isDaemon()).as(thread.getName()).isTrue());
        for (String pool : List.of("executorService", "executorService2")) {
            java.lang.reflect.Field field = ReactiveMessageSender.class.getDeclaredField(pool);
            field.setAccessible(true);
            assertThat(((ThreadPoolExecutor) field.get(messageSender)).allowsCoreThreadTimeOut())
                    .as("idle threads of %s are released", pool).isTrue();
        }
    }

    @Test
    void theLatencyAndTheSizeOfAPublishedMessageAreMeasured() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (ReactiveMessageSender meteredSender = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                unroutableMessageNotifier, new SenderSettings(false, Duration.ofSeconds(5), 13, 1024,
                ReactiveMessageSender.DEFAULT_MAX_MESSAGE_SIZE, new ConnectionBlockedState(), registry,
                "test-domain"))) {
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
        }
    }

    @Test
    void aMessageLargerThanTheMaximumSizeIsRejectedBeforeBeingWrittenAndCountedAsTooLarge() {
        when(sender.sendWithPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation ->
                Flux.from(invocation.<Publisher<OutboundMessage>>getArgument(0))
                        .map(message -> new OutboundMessageResult<>(message, true)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (ReactiveMessageSender limitedSender = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                unroutableMessageNotifier, new SenderSettings(false, Duration.ofSeconds(5), 13, 1024, 64,
                new ConnectionBlockedState(), registry, "test-domain"))) {
            SomeClass large = new SomeClass("42", "x".repeat(100), new Date());

            StepVerifier.create(limitedSender.sendWithConfirm(large, "exchange", "rkey", new HashMap<>(), true))
                    .verifyError(SendFailureMessageTooLargeException.class);
            StepVerifier.create(limitedSender.sendNoConfirm(large, "exchange", "rkey", new HashMap<>(), false))
                    .verifyError(SendFailureMessageTooLargeException.class);
            StepVerifier.create(limitedSender.sendWithConfirmBatch(Flux.just(large), "exchange", "rkey",
                            new HashMap<>(), true))
                    .verifyError(SendFailureMessageTooLargeException.class);
            StepVerifier.create(limitedSender.sendWithConfirm(new SomeClass("1", "a", null), "exchange", "rkey",
                            new HashMap<>(), true))
                    .verifyComplete();

            for (String confirm : List.of("true", "false")) {
                Counter tooLarge = registry.find("reactive_commons.publish.outcome")
                        .tags("domain", "test-domain", "confirm", confirm, "outcome", "too_large").counter();
                assertThat(tooLarge).as("too_large outcome, confirm=%s", confirm).isNotNull();
                assertThat(tooLarge.count()).isEqualTo("true".equals(confirm) ? 2 : 1);
            }
        }
    }

    @Test
    void aPublishWaitsForTheDeclarationOfItsExchangeAndGoesOnWhenItFails() {
        TopologyCreator topologyCreator = mock(TopologyCreator.class);
        Sinks.One<AMQP.Exchange.DeclareOk> declared = Sinks.one();
        when(topologyCreator.declare(any(ExchangeSpecification.class)))
                .thenReturn(declared.asMono())
                .thenReturn(Mono.error(new TopologyCreator.TopologyDefException(new IllegalStateException("406"))));
        try (ReactiveMessageSender declaringSender = new ReactiveMessageSender(sender, "TestApp", messageConverter,
                topologyCreator, false, unroutableMessageNotifier)) {
            declaringSender.declareExchange(ExchangeSpecification.exchange("exchange"));
            AtomicBoolean published = new AtomicBoolean();
            declaringSender.sendWithConfirm(new SomeClass("1", "a", null), "exchange", "rkey", new HashMap<>(), true)
                    .subscribe(ignored -> {
                    }, error -> {
                    }, () -> published.set(true));

            // other exchanges do not wait
            StepVerifier.create(declaringSender.sendWithConfirm(new SomeClass("2", "b", null), "other", "rkey",
                    new HashMap<>(), true)).verifyComplete();
            assertThat(published).as("published before its exchange is declared").isFalse();

            declared.tryEmitValue(mock(AMQP.Exchange.DeclareOk.class));
            await().atMost(Duration.ofSeconds(5)).untilTrue(published);

            // a declaration that fails does not fail the publishes
            declaringSender.declareExchange(ExchangeSpecification.exchange("exchange"));
            StepVerifier.create(declaringSender.sendWithConfirm(new SomeClass("3", "c", null), "exchange", "rkey",
                    new HashMap<>(), true)).verifyComplete();
        }
    }

    /**
     * A sender created after another one closed, with the same tags, gets gauges of its own instead of those bound to
     * the closed sender.
     */
    @Test
    void closingTheSenderRemovesItsMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReactiveMessageSender closed = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                unroutableMessageNotifier, new SenderSettings(false, Duration.ofSeconds(5), 13, 1024,
                ReactiveMessageSender.DEFAULT_MAX_MESSAGE_SIZE, new ConnectionBlockedState(), registry, "test-domain"));
        StepVerifier.create(closed.sendWithConfirm(new SomeClass("1", "a", null), "exchange", "rkey",
                new HashMap<>(), true)).verifyComplete();
        assertThat(registry.getMeters()).isNotEmpty();

        closed.close();

        assertThat(registry.getMeters()).as("meters of the closed sender").isEmpty();
    }

    @Test
    void theFlowResubscriptionCountersExistAtZeroBeforeAnyFlowIsResubscribed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (ReactiveMessageSender meteredSender = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                unroutableMessageNotifier, new SenderSettings(false, Duration.ofSeconds(5), 13, 1024,
                ReactiveMessageSender.DEFAULT_MAX_MESSAGE_SIZE, new ConnectionBlockedState(), registry,
                "test-domain"))) {
            for (String kind : List.of("confirm", "noConfirm")) {
                Counter resubscribed = registry.find("reactive_commons.publish.flow.resubscribe")
                        .tags("domain", "test-domain", "kind", kind).counter();
                assertThat(resubscribed).as("kind=%s registered with the sender", kind).isNotNull();
                assertThat(resubscribed.count()).isZero();
            }
        }
    }

    /**
     * A confirm that arrives after the sendTimeout reported to the caller does not count the message a second time:
     * publish.outcome has one outcome per message, the one its caller got.
     */
    @Test
    void aConfirmArrivingAfterTheSendTimeoutDoesNotCountTheMessageTwice() {
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            Flux<OutboundMessage> argument = invocation.getArgument(0);
            return argument.delayElements(Duration.ofMillis(300)).map(msg -> new OutboundMessageResult<>(msg, true));
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (ReactiveMessageSender meteredSender = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                unroutableMessageNotifier, new SenderSettings(false, Duration.ofMillis(100), 13, 1024,
                ReactiveMessageSender.DEFAULT_MAX_MESSAGE_SIZE, new ConnectionBlockedState(), registry,
                "test-domain"))) {
            StepVerifier.create(meteredSender.sendWithConfirm(new SomeClass("42", "Daniel", new Date()), "exchange",
                            "rkey", new HashMap<>(), true))
                    .expectError(SendConfirmUnknownException.class)
                    .verify(Duration.ofSeconds(5));
            // the late confirm arrives
            await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(2))
                    .until(() -> outcomeCount(registry, "ack") == 0);

            assertThat(outcomeCount(registry, "timeout")).isEqualTo(1);
            assertThat(outcomeCount(registry, "ack")).as("the late confirm is not a second outcome").isZero();
        }
    }

    @Test
    void thePublishMetersAreTaggedWithAnEmptyDomainWhenTheSenderHasNone() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (ReactiveMessageSender ignored = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                unroutableMessageNotifier, new SenderSettings(false, Duration.ofSeconds(5), 13, 1024,
                ReactiveMessageSender.DEFAULT_MAX_MESSAGE_SIZE, new ConnectionBlockedState(), registry, null))) {
            assertThat(registry.find("reactive_commons.publish.outcome").tags("domain", "", "outcome", "ack")
                    .counter()).as("same tag keys as the senders of a domain, as Prometheus requires").isNotNull();
        }
    }

    private static double outcomeCount(SimpleMeterRegistry registry, String outcome) {
        return registry.find("reactive_commons.publish.outcome")
                .tags("domain", "test-domain", "confirm", "true", "outcome", outcome).counter().count();
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
        try (ReactiveMessageSender meteredSender = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                unroutableMessageNotifier, new SenderSettings(false, Duration.ofSeconds(5), 13, 1024,
                ReactiveMessageSender.DEFAULT_MAX_MESSAGE_SIZE, new ConnectionBlockedState(), registry,
                "test-domain"))) {
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
            Counter nackCounter = registry.find("reactive_commons.publish.outcome")
                    .tag("outcome", "nack").counter();
            assertThat(nackCounter).as("outcome=nack is pre-registered at 0").isNotNull();
            assertThat(nackCounter.count()).as("the broker never nacked it").isZero();
            assertThat(registry.find("reactive_commons.publish.latency").tag("outcome", "nack").timer()).isNull();
        }
    }

    @Test
    void sendWithConfirmFailsWhenTheBrokerReturnsTheMessage() {
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            Flux<OutboundMessage> argument = invocation.getArgument(0);
            return argument.map(msg -> new OutboundMessageResult<>(msg, true, true));
        });
        // the flows are subscribed when the sender is created: use a new sender so it sees this stub
        try (ReactiveMessageSender returningSender = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                false, unroutableMessageNotifier)) {
            StepVerifier.create(returningSender.sendWithConfirm(new SomeClass("42", "Daniel", new Date()), "exchange",
                            "rkey", new HashMap<>(), true))
                    .expectError(SendFailureReturnedException.class)
                    .verify(Duration.ofSeconds(5));
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
    void sendNoConfirmPublishesTheTimeToLiveAsTheExpirationOfTheMessage() {
        List<String> expirations = new CopyOnWriteArrayList<>();
        when(sender.send(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            Flux.<OutboundMessage>from(invocation.getArgument(0))
                    .subscribe(message -> expirations.add(message.getProperties().getExpiration()));
            return Mono.never();
        });
        ReactiveMessageSender querySender = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                false, unroutableMessageNotifier);

        querySender.sendNoConfirm("query", "exchange", "target.query", new HashMap<>(), false,
                Duration.ofMillis(1_500)).subscribe(null, error -> {
        });
        // never negative: the broker would close the channel shared with other messages
        querySender.sendNoConfirm("query", "exchange", "target.query", new HashMap<>(), false,
                Duration.ofMillis(-1)).subscribe(null, error -> {
        });
        querySender.sendNoConfirm("event", "exchange", "rkey", new HashMap<>(), false)
                .subscribe(null, error -> {
                });

        await().atMost(Duration.ofSeconds(5)).until(() -> expirations.size() == 3);
        assertThat(expirations).containsExactlyInAnyOrder("1500", "0", null);
        querySender.close();
    }

    @Test
    void sendWithConfirmBatchFailsWithUnknownOutcomeWhenNoConfirmationArrives() {
        when(sender.sendWithPublishConfirms(any(Publisher.class), any(SendOptions.class))).thenReturn(Flux.never());
        try (ReactiveMessageSender shortTimeoutSender = new ReactiveMessageSender(sender, "TestApp", messageConverter,
                null, false, unroutableMessageNotifier, Duration.ofMillis(100))) {
            StepVerifier.create(shortTimeoutSender.sendWithConfirmBatch(Flux.just(new SomeClass("1", "a", new Date())),
                            "exchange", "rkey", new HashMap<>(), true))
                    .expectError(SendConfirmUnknownException.class)
                    .verify(Duration.ofSeconds(5));
        }
    }

    @Test
    void closeLetsTheMessagesAlreadyWrittenGetTheirConfirm() {
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation ->
                ((Flux<OutboundMessage>) invocation.getArgument(0))
                        .delayElements(Duration.ofMillis(300)) // the broker confirms a while after the write
                        .map(message -> new OutboundMessageResult<>(message, true)));
        ReactiveMessageSender senderToClose = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                false, unroutableMessageNotifier);

        StepVerifier.create(senderToClose.sendWithConfirm(new SomeClass("42", "Daniel", new Date()), "exchange",
                        "rkey", new HashMap<>(), true))
                .then(senderToClose::close)
                .verifyComplete();
    }

    @Test
    void closeReportsAMessageWrittenAndNotConfirmedAsOfUnknownOutcome() {
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            ((Flux<OutboundMessage>) invocation.getArgument(0)).subscribe(); // written, never confirmed
            return Flux.never();
        });
        ReactiveMessageSender senderToClose = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                false, unroutableMessageNotifier);

        StepVerifier.create(senderToClose.sendWithConfirm(new SomeClass("42", "Daniel", new Date()), "exchange",
                        "rkey", new HashMap<>(), true))
                .then(senderToClose::close)
                // it may have reached the broker: reporting it as not published would be wrong
                .expectError(SendConfirmUnknownException.class)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void closeReportsAMessageNeverWrittenAsNotPublished() {
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class))).then(invocation -> {
            ((Flux<OutboundMessage>) invocation.getArgument(0)).subscribe(new BaseSubscriber<>() {
                @Override
                protected void hookOnSubscribe(Subscription subscription) {
                    // no demand: the messages wait in the buffer of the flow, as while its channel is busy
                }
            });
            return Flux.never();
        });
        ReactiveMessageSender senderToClose = new ReactiveMessageSender(sender, "TestApp", messageConverter, null,
                false, unroutableMessageNotifier);

        StepVerifier.create(senderToClose.sendWithConfirm(new SomeClass("42", "Daniel", new Date()), "exchange",
                        "rkey", new HashMap<>(), true))
                .then(senderToClose::close)
                .expectErrorSatisfies(error -> assertThat(error).isExactlyInstanceOf(SendFailureNoAckException.class))
                .verify(Duration.ofSeconds(10));
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
    void logsOnlyTheFirstResubscribeOfAFlowAtWarning() {
        when(sender.sendWithTypedPublishConfirms(any(Publisher.class), any(SendOptions.class)))
                .thenReturn(Flux.error(new RabbitFluxException("boom")));
        List<ILoggingEvent> records = new CopyOnWriteArrayList<>();
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                records.add(event);
            }
        };
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(ReactiveMessageSender.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try (ReactiveMessageSender ignored = new ReactiveMessageSender(sender, "TestApp", messageConverter, null, false,
                unroutableMessageNotifier)) {
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> count(records, "publisher-confirms flow #0", Level.WARN) >= 1
                            && count(records, "publisher-confirms flow #0", Level.DEBUG) >= 1);

            assertThat(count(records, "publisher-confirms flow #0", Level.WARN)).isEqualTo(1);
            assertThat(count(records, "publisher-confirms flow #0", Level.DEBUG)).isGreaterThanOrEqualTo(1);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    private static long count(List<ILoggingEvent> records, String flowName, Level level) {
        return records.stream()
                .filter(event -> level.equals(event.getLevel()))
                .filter(event -> event.getFormattedMessage().contains(flowName))
                .count();
    }

    private record SomeClass(String id, String name, Date date) {
    }

}