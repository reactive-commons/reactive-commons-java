package org.reactivecommons.async.rabbit.listeners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.commons.converters.json.JacksonMessageConverter;
import org.reactivecommons.async.utils.TestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.Receiver;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static reactor.core.publisher.Flux.range;

@ExtendWith(MockitoExtension.class)
class ApplicationCommandListenerPerfTest {


    private static final CountDownLatch latch = new CountDownLatch(12 + 1);
    private static final int messageCount = 40000;
    private final Semaphore semaphore = new Semaphore(0);
    @Mock
    private Receiver receiver;
    @Mock
    private TopologyCreator topologyCreator;
    @Mock
    private DiscardNotifier discardNotifier;
    @Mock
    private CustomReporter errorReporter;
    private StubGenericMessageListener messageListener;
    private MessageConverter messageConverter = new JacksonMessageConverter(new DefaultObjectMapperSupplier().get());
    private ReactiveMessageListener reactiveMessageListener;

    private static BigInteger makeHardWork() {
        final long number = ThreadLocalRandom.current().nextLong(100) + 2700;
        BigInteger fact = new BigInteger("1");
        for (long i = 1; i <= number; i++) {
            fact = fact.multiply(BigInteger.valueOf(i));
        }
        return fact;
    }

    @BeforeEach
    public void setUp() {
        reactiveMessageListener = new ReactiveMessageListener(receiver, topologyCreator);
    }

    private Mono<Void> handleTestMessage(Command<DummyMessage> message) {
        return Mono.fromRunnable(() -> {
            if (latch.getCount() > 0) {
                latch.countDown();
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            semaphore.release();
        });
    }

    @Test
    void shouldProcessMessagesInOptimalTime() throws JsonProcessingException, InterruptedException {
        HandlerResolver handlerResolver = createHandlerResolver(HandlerRegistry.register()
                .handleCommand("app.command.test", this::handleTestMessage, DummyMessage.class)
        );
        messageListener = new StubGenericMessageListener("test-queue", reactiveMessageListener, true, 10, discardNotifier, "command", handlerResolver, messageConverter, errorReporter);
        Flux<AcknowledgableDelivery> messageFlux = createSource(messageCount);
        TestUtils.instructSafeReceiverMock(receiver, messageFlux);

        messageListener.startListener();
        final long init = System.currentTimeMillis();
        latch.countDown();
        semaphore.acquire(messageCount);
        final long end = System.currentTimeMillis();

        final long total = end - init;
        final double microsPerMessage = ((total + 0.0) / messageCount) * 1000;
        System.out.println("Message count: " + messageCount);
        System.out.println("Total Execution Time: " + total + "ms");
        System.out.println("Microseconds per message: " + microsPerMessage + "us");
        if (System.getProperty("env.ci") == null) {
            Assertions.assertThat(microsPerMessage).isLessThan(75);
        }
    }

    private Mono<Void> handleTestMessageDelay(Command<DummyMessage> message) {
        return Mono.delay(Duration.ofMillis(10)).flatMap(aLong -> Mono.fromRunnable(semaphore::release));
    }

    @Test
    void shouldProcessAsyncMessagesConcurrent() throws JsonProcessingException, InterruptedException {
        HandlerResolver handlerResolver = createHandlerResolver(HandlerRegistry.register()
                .handleCommand("app.command.test", this::handleTestMessageDelay, DummyMessage.class)
        );
        messageListener = new StubGenericMessageListener("test-queue", reactiveMessageListener, true, 10, discardNotifier, "command", handlerResolver, messageConverter, errorReporter);
        Flux<AcknowledgableDelivery> messageFlux = createSource(messageCount);
        TestUtils.instructSafeReceiverMock(receiver, messageFlux);
        System.out.println("Permits before: " + semaphore.availablePermits());
        final long init = System.currentTimeMillis();
        messageListener.startListener();
        semaphore.acquire(messageCount);
        final long end = System.currentTimeMillis();

        final long total = end - init;
        final double microsPerMessage = ((total + 0.0) / messageCount) * 1000;
        System.out.println("Message count: " + messageCount);
        System.out.println("Total Execution Time: " + total + "ms");
        System.out.println("Microseconds per message: " + microsPerMessage + "us");
        if (System.getProperty("env.ci") == null) {
            Assertions.assertThat(microsPerMessage).isLessThan(120);
        }
    }

    private Mono<Void> handleTestCPUMessageDelay(Command<DummyMessage> message) {
        return Mono.fromRunnable(() -> {
            liveLock(10);
            semaphore.release();
        });
    }

    private Mono<Void> handleTestCPUWorkMessageDelay(Command<DummyMessage> message) {
        return Mono.fromRunnable(() -> {
            makeHardWork();
            semaphore.release();
        });
    }

    private Mono<Void> handleTestPassiveBlockMessageDelay(Command<DummyMessage> message) {
        return Mono.fromRunnable(() -> {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            semaphore.release();
        });
    }

    private void liveLock(int delay) {
        for (long end = System.currentTimeMillis() + delay; System.currentTimeMillis() < end; ) ;
    }



    @Test
    void shouldProcessCPUMessagesInParallel() throws JsonProcessingException, InterruptedException {
        HandlerResolver handlerResolver = createHandlerResolver(HandlerRegistry.register()
                .handleCommand("app.command.test", this::handleTestCPUMessageDelay, DummyMessage.class)
        );
        int messageCount = 2000;
        reactiveMessageListener = new ReactiveMessageListener(receiver, topologyCreator, 250, 250);
        messageListener = new StubGenericMessageListener("test-queue", reactiveMessageListener, true, 10, discardNotifier, "command", handlerResolver, messageConverter, errorReporter);
        Flux<AcknowledgableDelivery> messageFlux = createSource(messageCount);

        TestUtils.instructSafeReceiverMock(receiver, messageFlux);

        System.out.println("Permits before: " + semaphore.availablePermits());
        final long init = System.currentTimeMillis();
        messageListener.startListener();
        semaphore.acquire(messageCount);
        final long end = System.currentTimeMillis();

        final long total = end - init;
        final double microsPerMessage = ((total + 0.0) / messageCount) * 1000;
        System.out.println("Message count: " + messageCount);
        System.out.println("Total Execution Time: " + total + "ms");
        System.out.println("Microseconds per message: " + microsPerMessage + "us");
        if (System.getProperty("env.ci") == null) {
            Assertions.assertThat(microsPerMessage).isLessThan(2000);
        }
    }

    @Test
    void shouldProcessCPUWorkMessagesInParallel() throws JsonProcessingException, InterruptedException {
        HandlerResolver handlerResolver = createHandlerResolver(HandlerRegistry.register()
                .handleCommand("app.command.test", this::handleTestCPUWorkMessageDelay, DummyMessage.class)
        );
        int messageCount = 2000;
        reactiveMessageListener = new ReactiveMessageListener(receiver, topologyCreator, 500, 250);
        messageListener = new StubGenericMessageListener("test-queue", reactiveMessageListener, true, 10, discardNotifier, "command", handlerResolver, messageConverter, errorReporter);
        Flux<AcknowledgableDelivery> messageFlux = createSource(messageCount);

        TestUtils.instructSafeReceiverMock(receiver, messageFlux);

        System.out.println("Permits before: " + semaphore.availablePermits());
        final long init = System.currentTimeMillis();
        messageListener.startListener();
        semaphore.acquire(messageCount);
        final long end = System.currentTimeMillis();

        final long total = end - init;
        final double microsPerMessage = ((total + 0.0) / messageCount) * 1000;
        System.out.println("Message count: " + messageCount);
        System.out.println("Total Execution Time: " + total + "ms");
        System.out.println("Microseconds per message: " + microsPerMessage + "us");
        if (System.getProperty("env.ci") == null) {
            Assertions.assertThat(microsPerMessage).isLessThan(4350);
        }
    }

    @Test
    void shouldProcessPasiveBlockingMessagesInParallel() throws JsonProcessingException, InterruptedException {
        HandlerResolver handlerResolver = createHandlerResolver(HandlerRegistry.register()
                .handleCommand("app.command.test", this::handleTestPassiveBlockMessageDelay, DummyMessage.class)
        );
        int messageCount = 2000;
        reactiveMessageListener = new ReactiveMessageListener(receiver, topologyCreator, 500, 250);
        messageListener = new StubGenericMessageListener("test-queue", reactiveMessageListener, true, 10, discardNotifier, "command", handlerResolver, messageConverter, errorReporter);
        Flux<AcknowledgableDelivery> messageFlux = createSource(messageCount);

        TestUtils.instructSafeReceiverMock(receiver, messageFlux);

        System.out.println("Permits before: " + semaphore.availablePermits());
        final long init = System.currentTimeMillis();
        messageListener.startListener();
        semaphore.acquire(messageCount);
        final long end = System.currentTimeMillis();

        final long total = end - init;
        final double microsPerMessage = ((total + 0.0) / messageCount) * 1000;
        System.out.println("Message count: " + messageCount);
        System.out.println("Total Execution Time: " + total + "ms");
        System.out.println("Microseconds per message: " + microsPerMessage + "us");
        if (System.getProperty("env.ci") == null) {
            Assertions.assertThat(microsPerMessage).isLessThan(2200);
        }
    }

    private HandlerResolver createHandlerResolver(final HandlerRegistry initialRegistry) {
        final HandlerRegistry registry = range(0, 20).reduce(initialRegistry, (r, i) -> r.handleCommand("app.command.name" + i, message -> Mono.empty(), Map.class)).block();
        final ConcurrentMap<String, RegisteredCommandHandler<?, ?>> commandHandlers = registry.getCommandHandlers().stream()
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler), ConcurrentHashMap::putAll);
        return new HandlerResolver(null, null, null, null, commandHandlers) {
            @Override
            @SuppressWarnings("unchecked")
            public RegisteredCommandHandler<Object, ? extends Object> getCommandHandler(String path) {
                final RegisteredCommandHandler<Object, Object> handler = (RegisteredCommandHandler<Object, Object>) super.getCommandHandler(path);
                return handler != null ? handler : new RegisteredCommandHandler<Object, Command<Object>>("", new DefaultCommandHandler<Object>() {
                    @Override
                    public Mono<Void> handle(Command<Object> message) {
                        return Mono.error(new RuntimeException("Default handler in Test"));
                    }
                }, Object.class);
            }
        };
    }


    private Flux<AcknowledgableDelivery> createSource(int count) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        Command<DummyMessage> command = new Command<>("app.command.test", UUID.randomUUID().toString(), new DummyMessage());
        String data = mapper.writeValueAsString(command);
        final List<AcknowledgableDelivery> list = IntStream.range(0, count).mapToObj(value -> {
            AMQP.BasicProperties props = new AMQP.BasicProperties();
            Envelope envelope = new Envelope(count, true, data, data);
            Delivery delivery = new Delivery(envelope, props, data.getBytes());
            return new AcknowledgableDelivery(delivery, new ChannelDummy(), null);
        }).collect(Collectors.toList());

        return Flux.fromIterable(new ArrayList<>(list));
    }

    private AMQP.BasicProperties createProps() {
        return new AMQP.BasicProperties(
                "application/json",
                "UTF-8",
                null,
                null,
                null,
                null,
                null,
                null,
                "3242",
                null,
                null,
                null,
                null,
                null);
    }

    class StubGenericMessageListener extends ApplicationCommandListener {

        public StubGenericMessageListener(String queueName, ReactiveMessageListener listener, boolean useDLQRetries, long maxRetries, DiscardNotifier discardNotifier, String objectType, HandlerResolver handlerResolver, MessageConverter messageConverter, CustomReporter errorReporter) {
            super(listener, queueName, handlerResolver, "directExchange", messageConverter, true, false, false, 10, 10, Optional.empty(), discardNotifier, errorReporter);
        }

    }
}





