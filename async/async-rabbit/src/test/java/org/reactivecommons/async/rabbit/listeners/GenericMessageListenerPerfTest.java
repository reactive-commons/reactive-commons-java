package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.*;
import lombok.Data;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.Receiver;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class GenericMessageListenerPerfTest {

    @Mock
    private Receiver receiver;

    @Mock
    private TopologyCreator topologyCreator;

    @Mock
    private DiscardNotifier discardNotifier;

    @Mock
    private CustomReporter errorReporter;

    private StubGenericMessageListener messageListener;

    private static final int messageCount = 40000;
    private final Semaphore semaphore = new Semaphore(0);

    @BeforeEach
    void init() {
//        when(errorReporter.reportError(any(Throwable.class), any(Message.class), any(Object.class))).thenReturn(Mono.empty());
        ReactiveMessageListener reactiveMessageListener = new ReactiveMessageListener(receiver, topologyCreator);
        messageListener = new StubGenericMessageListener(
                "test-queue", reactiveMessageListener, true, true, 10, discardNotifier, "command", errorReporter
        );
    }


    @Test
    void shouldProcessMessagesInOptimalTime() throws InterruptedException {
        Flux<AcknowledgableDelivery> messageFlux = createSource(messageCount);
        when(receiver.consumeManualAck(Mockito.anyString(), Mockito.any(ConsumeOptions.class))).thenReturn(messageFlux);
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
            Assertions.assertThat(microsPerMessage).isLessThan(65);
        }
    }

    @Test
    void referenceTime() throws InterruptedException {
        Flux<AcknowledgableDelivery> fakeSource = createSource(1);
        Flux<AcknowledgableDelivery> messageFlux = createSource(messageCount);

        when(receiver.consumeManualAck(Mockito.anyString(), Mockito.any(ConsumeOptions.class))).thenReturn(fakeSource);
        final long init = System.currentTimeMillis();
        messageListener.startListener();
        messageFlux.flatMap(d -> messageListener.rawMessageHandler("").apply(null)).subscribe();
        semaphore.acquire(messageCount + 1);
        final long end = System.currentTimeMillis();
        final long total = end - init;
        final double microsPerLookup = ((total + 0.0) / messageCount) * 1000;
        System.out.println("Message count: " + messageCount);
        System.out.println("Total Execution Time: " + total + "ms");
        System.out.println("Microseconds per message: " + microsPerLookup + "us");
    }

    private Flux<AcknowledgableDelivery> createSource(int count) {
        JsonMapper mapper = new JsonMapper();
        Command<DummyMessage> command = new Command<>(
                "some.command.name", UUID.randomUUID().toString(), new DummyMessage()
        );
        String data = mapper.writeValueAsString(command);
        final List<AcknowledgableDelivery> list = IntStream.range(0, count).mapToObj(value -> {
            AMQP.BasicProperties props = new AMQP.BasicProperties();
            Envelope envelope = new Envelope(count, true, data, data);
            Delivery delivery = new Delivery(envelope, props, data.getBytes());
            return new AcknowledgableDelivery(delivery, new ChannelDummy(), null);
        }).toList();

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

    class StubGenericMessageListener extends GenericMessageListener {

        public StubGenericMessageListener(String queueName, ReactiveMessageListener listener, boolean useDLQRetries,
                                          boolean createTopology, long maxRetries, DiscardNotifier discardNotifier,
                                          String objectType, CustomReporter errorReporter) {
            super(queueName, listener, useDLQRetries, createTopology, maxRetries, 200, discardNotifier,
                    objectType, errorReporter);
        }

        @Override
        public Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
            return message -> Mono.fromRunnable(semaphore::release);
        }

        @Override
        protected String getExecutorPath(AcknowledgableDelivery msj) {
            return "test-path";
        }

        @Override
        protected Object parseMessageForReporter(Message msj) {
            return null;
        }

        @Override
        protected String getKind() {
            return "stub";
        }
    }
}

@Data
class DummyMessage {
    private String name = "Daniel" + ThreadLocalRandom.current().nextLong();
    private Long age = ThreadLocalRandom.current().nextLong();
    private String field1 = "Field Data " + ThreadLocalRandom.current().nextLong();
    private String field2 = "Field Data " + ThreadLocalRandom.current().nextLong();
    private String field3 = "Field Data " + ThreadLocalRandom.current().nextLong();
    private String field4 = "Field Data " + ThreadLocalRandom.current().nextLong();
}


class ChannelDummy implements Channel {
    @Override
    public int getChannelNumber() {
        return 0;
    }

    @Override
    public Connection getConnection() {
        return null;
    }

    @Override
    public void close() throws IOException, TimeoutException {

    }

    @Override
    public void close(int closeCode, String closeMessage) throws IOException, TimeoutException {

    }

    @Override
    public void abort() {

    }

    @Override
    public void abort(int closeCode, String closeMessage) {

    }

    @Override
    public void addReturnListener(ReturnListener listener) {

    }

    @Override
    public ReturnListener addReturnListener(ReturnCallback returnCallback) {
        return null;
    }

    @Override
    public boolean removeReturnListener(ReturnListener listener) {
        return false;
    }

    @Override
    public void clearReturnListeners() {

    }

    @Override
    public void addConfirmListener(ConfirmListener listener) {

    }

    @Override
    public ConfirmListener addConfirmListener(ConfirmCallback ackCallback, ConfirmCallback nackCallback) {
        return null;
    }

    @Override
    public boolean removeConfirmListener(ConfirmListener listener) {
        return false;
    }

    @Override
    public void clearConfirmListeners() {

    }

    @Override
    public Consumer getDefaultConsumer() {
        return null;
    }

    @Override
    public void setDefaultConsumer(Consumer consumer) {

    }

    @Override
    public void basicQos(int prefetchSize, int prefetchCount, boolean global) {

    }

    @Override
    public void basicQos(int prefetchCount, boolean global) {

    }

    @Override
    public void basicQos(int prefetchCount) {

    }

    @Override
    public void basicPublish(String exchange, String routingKey, AMQP.BasicProperties props, byte[] body) {

    }

    @Override
    public void basicPublish(String exchange, String routingKey, boolean mandatory, AMQP.BasicProperties props, byte[] body) {

    }

    @Override
    public void basicPublish(String exchange, String routingKey, boolean mandatory, boolean immediate, AMQP.BasicProperties props, byte[] body) {

    }

    @Override
    public AMQP.Exchange.DeclareOk exchangeDeclare(String exchange, String type) {
        return null;
    }

    @Override
    public AMQP.Exchange.DeclareOk exchangeDeclare(String exchange, BuiltinExchangeType type) {
        return null;
    }

    @Override
    public AMQP.Exchange.DeclareOk exchangeDeclare(String exchange, String type, boolean durable) {
        return null;
    }

    @Override
    public AMQP.Exchange.DeclareOk exchangeDeclare(String exchange, BuiltinExchangeType type, boolean durable) {
        return null;
    }

    @Override
    public AMQP.Exchange.DeclareOk exchangeDeclare(String exchange, String type, boolean durable, boolean autoDelete, Map<String, Object> arguments) {
        return null;
    }

    @Override
    public AMQP.Exchange.DeclareOk exchangeDeclare(String exchange, BuiltinExchangeType type, boolean durable, boolean autoDelete, Map<String, Object> arguments) {
        return null;
    }

    @Override
    public AMQP.Exchange.DeclareOk exchangeDeclare(String exchange, String type, boolean durable, boolean autoDelete, boolean internal, Map<String, Object> arguments) {
        return null;
    }

    @Override
    public AMQP.Exchange.DeclareOk exchangeDeclare(String exchange, BuiltinExchangeType type, boolean durable, boolean autoDelete, boolean internal, Map<String, Object> arguments) {
        return null;
    }

    @Override
    public void exchangeDeclareNoWait(String exchange, String type, boolean durable, boolean autoDelete, boolean internal, Map<String, Object> arguments) {

    }

    @Override
    public void exchangeDeclareNoWait(String exchange, BuiltinExchangeType type, boolean durable, boolean autoDelete, boolean internal, Map<String, Object> arguments) {

    }

    @Override
    public AMQP.Exchange.DeclareOk exchangeDeclarePassive(String name) {
        return null;
    }

    @Override
    public AMQP.Exchange.DeleteOk exchangeDelete(String exchange, boolean ifUnused) {
        return null;
    }

    @Override
    public void exchangeDeleteNoWait(String exchange, boolean ifUnused) {

    }

    @Override
    public AMQP.Exchange.DeleteOk exchangeDelete(String exchange) {
        return null;
    }

    @Override
    public AMQP.Exchange.BindOk exchangeBind(String destination, String source, String routingKey) {
        return null;
    }

    @Override
    public AMQP.Exchange.BindOk exchangeBind(String destination, String source, String routingKey, Map<String, Object> arguments) {
        return null;
    }

    @Override
    public void exchangeBindNoWait(String destination, String source, String routingKey, Map<String, Object> arguments) {

    }

    @Override
    public AMQP.Exchange.UnbindOk exchangeUnbind(String destination, String source, String routingKey) {
        return null;
    }

    @Override
    public AMQP.Exchange.UnbindOk exchangeUnbind(String destination, String source, String routingKey, Map<String, Object> arguments) {
        return null;
    }

    @Override
    public void exchangeUnbindNoWait(String destination, String source, String routingKey, Map<String, Object> arguments) {

    }

    @Override
    public AMQP.Queue.DeclareOk queueDeclare() {
        return null;
    }

    @Override
    public AMQP.Queue.DeclareOk queueDeclare(String queue, boolean durable, boolean exclusive, boolean autoDelete, Map<String, Object> arguments) {
        return null;
    }

    @Override
    public void queueDeclareNoWait(String queue, boolean durable, boolean exclusive, boolean autoDelete, Map<String, Object> arguments) {

    }

    @Override
    public AMQP.Queue.DeclareOk queueDeclarePassive(String queue) {
        return null;
    }

    @Override
    public AMQP.Queue.DeleteOk queueDelete(String queue) {
        return null;
    }

    @Override
    public AMQP.Queue.DeleteOk queueDelete(String queue, boolean ifUnused, boolean ifEmpty) {
        return null;
    }

    @Override
    public void queueDeleteNoWait(String queue, boolean ifUnused, boolean ifEmpty) {

    }

    @Override
    public AMQP.Queue.BindOk queueBind(String queue, String exchange, String routingKey) {
        return null;
    }

    @Override
    public AMQP.Queue.BindOk queueBind(String queue, String exchange, String routingKey, Map<String, Object> arguments) {
        return null;
    }

    @Override
    public void queueBindNoWait(String queue, String exchange, String routingKey, Map<String, Object> arguments) {

    }

    @Override
    public AMQP.Queue.UnbindOk queueUnbind(String queue, String exchange, String routingKey) {
        return null;
    }

    @Override
    public AMQP.Queue.UnbindOk queueUnbind(String queue, String exchange, String routingKey, Map<String, Object> arguments) {
        return null;
    }

    @Override
    public AMQP.Queue.PurgeOk queuePurge(String queue) {
        return null;
    }

    @Override
    public GetResponse basicGet(String queue, boolean autoAck) {
        return null;
    }

    @Override
    public void basicAck(long deliveryTag, boolean multiple) {

    }

    @Override
    public void basicNack(long deliveryTag, boolean multiple, boolean requeue) {

    }

    @Override
    public void basicReject(long deliveryTag, boolean requeue) {

    }

    @Override
    public String basicConsume(String queue, Consumer callback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, DeliverCallback deliverCallback, CancelCallback cancelCallback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, DeliverCallback deliverCallback, ConsumerShutdownSignalCallback shutdownSignalCallback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, DeliverCallback deliverCallback, CancelCallback cancelCallback, ConsumerShutdownSignalCallback shutdownSignalCallback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, Consumer callback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, DeliverCallback deliverCallback, CancelCallback cancelCallback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, DeliverCallback deliverCallback, ConsumerShutdownSignalCallback shutdownSignalCallback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, DeliverCallback deliverCallback, CancelCallback cancelCallback, ConsumerShutdownSignalCallback shutdownSignalCallback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, Map<String, Object> arguments, Consumer callback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, Map<String, Object> arguments, DeliverCallback deliverCallback, CancelCallback cancelCallback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, Map<String, Object> arguments, DeliverCallback deliverCallback, ConsumerShutdownSignalCallback shutdownSignalCallback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, Map<String, Object> arguments, DeliverCallback deliverCallback, CancelCallback cancelCallback, ConsumerShutdownSignalCallback shutdownSignalCallback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, String consumerTag, Consumer callback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, String consumerTag, DeliverCallback deliverCallback, CancelCallback cancelCallback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, String consumerTag, DeliverCallback deliverCallback, ConsumerShutdownSignalCallback shutdownSignalCallback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, String consumerTag, DeliverCallback deliverCallback, CancelCallback cancelCallback, ConsumerShutdownSignalCallback shutdownSignalCallback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, String consumerTag, boolean noLocal, boolean exclusive, Map<String, Object> arguments, Consumer callback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, String consumerTag, boolean noLocal, boolean exclusive, Map<String, Object> arguments, DeliverCallback deliverCallback, CancelCallback cancelCallback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, String consumerTag, boolean noLocal, boolean exclusive, Map<String, Object> arguments, DeliverCallback deliverCallback, ConsumerShutdownSignalCallback shutdownSignalCallback) {
        return null;
    }

    @Override
    public String basicConsume(String queue, boolean autoAck, String consumerTag, boolean noLocal, boolean exclusive,
                               Map<String, Object> arguments, DeliverCallback deliverCallback,
                               CancelCallback cancelCallback,
                               ConsumerShutdownSignalCallback shutdownSignalCallback) {
        return null;
    }

    @Override
    public void basicCancel(String consumerTag) {

    }

    @Override
    public AMQP.Basic.RecoverOk basicRecover() {
        return null;
    }

    @Override
    public AMQP.Basic.RecoverOk basicRecover(boolean requeue) {
        return null;
    }

    @Override
    public AMQP.Tx.SelectOk txSelect() {
        return null;
    }

    @Override
    public AMQP.Tx.CommitOk txCommit() {
        return null;
    }

    @Override
    public AMQP.Tx.RollbackOk txRollback() {
        return null;
    }

    @Override
    public AMQP.Confirm.SelectOk confirmSelect() {
        return null;
    }

    @Override
    public long getNextPublishSeqNo() {
        return 0;
    }

    @Override
    public boolean waitForConfirms() throws InterruptedException {
        return false;
    }

    @Override
    public boolean waitForConfirms(long timeout) throws InterruptedException, TimeoutException {
        return false;
    }

    @Override
    public void waitForConfirmsOrDie() throws IOException, InterruptedException {

    }

    @Override
    public void waitForConfirmsOrDie(long timeout) throws IOException, InterruptedException, TimeoutException {

    }

    @Override
    public void asyncRpc(Method method) {

    }

    @Override
    public com.rabbitmq.client.Command rpc(Method method) {
        return null;
    }

    @Override
    public long messageCount(String queue) {
        return 0;
    }

    @Override
    public long consumerCount(String queue) {
        return 0;
    }

    @Override
    public CompletableFuture<com.rabbitmq.client.Command> asyncCompletableRpc(Method method) {
        return null;
    }

    @Override
    public void addShutdownListener(ShutdownListener listener) {

    }

    @Override
    public void removeShutdownListener(ShutdownListener listener) {

    }

    @Override
    public ShutdownSignalException getCloseReason() {
        return null;
    }

    @Override
    public void notifyListeners() {

    }

    @Override
    public boolean isOpen() {
        return false;
    }
}


