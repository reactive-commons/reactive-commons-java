package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.From;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageNotifier;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.SendOptions;
import reactor.rabbitmq.Sender;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.commons.Headers.COMPLETION_ONLY_SIGNAL;
import static org.reactivecommons.async.commons.Headers.CORRELATION_ID;
import static org.reactivecommons.async.commons.Headers.REPLY_ID;


@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class RabbitDirectAsyncGatewayTest {

    private final BrokerConfig config = new BrokerConfig();
    private final Semaphore semaphore = new Semaphore(0);
    private final MessageConverter converter = new RabbitJacksonMessageConverter(
            new DefaultObjectMapperSupplier().get()
    );
    @Mock
    private ReactiveReplyRouter router;
    @Mock
    private ReactiveMessageSender senderMock;
    @Mock
    private UnroutableMessageNotifier unroutableMessageNotifier;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private RabbitDirectAsyncGateway asyncGateway;

    void init(ReactiveMessageSender sender) {
        asyncGateway = new RabbitDirectAsyncGateway(
                config, router, sender, "exchange", converter, meterRegistry
        );
    }

    @Test
    void shouldReleaseRouterResourcesOnTimeout() {
        var brokerConfig = new BrokerConfig(
                false, false, false, Duration.ofSeconds(1)
        );
        asyncGateway = new RabbitDirectAsyncGateway(
                brokerConfig, router, senderMock, "ex", converter, meterRegistry
        );
        when(router.register(anyString())).thenReturn(Mono.never());
        when(senderMock.sendNoConfirm(any(), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(Mono.empty());

        AsyncQuery<String> query = new AsyncQuery<>("some.query", "data");
        asyncGateway.requestReply(query, "some.target", String.class)
                .as(StepVerifier::create)
                .expectError(TimeoutException.class)
                .verify();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(router).register(captor.capture());
        verify(router).deregister(captor.getValue());
    }

    @Test
    void shouldSendInOptimalTime() throws InterruptedException {
        init(getReactiveMessageSender());

        int messageCount = 40000;
        final Flux<Command<DummyMessage>> messages = createMessagesHot(messageCount);
        final Flux<Void> target =
                messages.flatMap(dummyMessageCommand ->
                        asyncGateway.sendCommand(dummyMessageCommand, "testTarget")
                                .doOnSuccess(aVoid -> semaphore.release())
                                .doOnError(Throwable::printStackTrace)
                );

        final long init = System.currentTimeMillis();
        target.subscribe();
        semaphore.acquire(messageCount);
        final long end = System.currentTimeMillis();

        final long total = end - init;
        final double microsPerMessage = ((total + 0.0) / messageCount) * 1000;
        System.out.println("Message count: " + messageCount);
        System.out.println("Total Execution Time: " + total + "ms");
        System.out.println("Microseconds per message: " + microsPerMessage + "us");
        if (System.getProperty("env.ci") == null) {
            assertThat(microsPerMessage).isLessThan(150);
        }
    }

    @Test
    void shouldReplyQuery() {
        // Arrange
        senderMock();

        From from = new From();
        from.setReplyID("replyId");
        from.setCorrelationID("correlationId");
        DummyMessage response = new DummyMessage();
        // Act
        Mono<Void> result = asyncGateway.reply(response, from);
        // Assert
        StepVerifier.create(result).verifyComplete();
        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(senderMock, times(1))
                .sendNoConfirm(eq(response), eq("globalReply"), eq("replyId"),
                        headersCaptor.capture(), anyBoolean()
                );
        assertThat(headersCaptor.getValue()).containsEntry(CORRELATION_ID, "correlationId");
    }

    @Test
    void shouldReplyQueryWithout() {
        // Arrange
        senderMock();

        From from = new From();
        from.setReplyID("replyId");
        from.setCorrelationID("correlationId");
        // Act
        Mono<Void> result = asyncGateway.reply(null, from);
        // Assert
        StepVerifier.create(result).verifyComplete();
        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(senderMock, times(1))
                .sendNoConfirm(
                        eq(null), eq("globalReply"), eq("replyId"), headersCaptor.capture(),
                        anyBoolean()
                );
        assertThat(headersCaptor.getValue())
                .containsEntry(CORRELATION_ID, "correlationId")
                .containsEntry(COMPLETION_ONLY_SIGNAL, Boolean.TRUE.toString());
    }

    @Test
    void shouldHandleRequestReply() {
        senderMock();
        mockReply();

        String queryName = "my.query";
        String targetName = "app-target";
        AsyncQuery<DummyMessage> query = new AsyncQuery<>(queryName, new DummyMessage());

        Mono<DummyMessage> result = asyncGateway.requestReply(query, targetName, DummyMessage.class);

        StepVerifier.create(result)
                .assertNext(res -> assertThat(res.getName()).startsWith("Daniel"))
                .verifyComplete();
        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(senderMock, times(1))
                .sendNoConfirm(eq(query), eq("exchange"), eq("app-target.query"), headersCaptor.capture(),
                        anyBoolean());
        assertThat(headersCaptor.getValue().get(REPLY_ID).toString()).hasSize(32);
        assertThat(headersCaptor.getValue().get(CORRELATION_ID).toString()).hasSize(32);
    }

    @Test
    void shouldSendCommandWithDefaultDomain() {
        init(senderMock);
        when(senderMock.sendWithConfirm(any(), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(Mono.empty());

        Command<DummyMessage> command = new Command<>("test.command", "id", new DummyMessage());

        StepVerifier.create(asyncGateway.sendCommand(command, "target"))
                .verifyComplete();

        verify(senderMock)
                .sendWithConfirm(eq(command), eq("exchange"), eq("target"), anyMap(), anyBoolean());
    }

    @Test
    void shouldSendCommandWithDelay() {
        init(senderMock);
        when(senderMock.sendWithConfirm(any(), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(Mono.empty());

        Command<DummyMessage> command = new Command<>("test.command", "id", new DummyMessage());

        StepVerifier.create(asyncGateway.sendCommand(command, "target", 5000L))
                .verifyComplete();

        ArgumentCaptor<String> routingKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(senderMock).sendWithConfirm(eq(command), eq("exchange"), routingKeyCaptor.capture(),
                headersCaptor.capture(), anyBoolean());
        assertThat(routingKeyCaptor.getValue()).isEqualTo("target-delayed");
        assertThat(headersCaptor.getValue()).containsEntry("rc-delay", "5000");
    }

    @Test
    void shouldSendCommandWithZeroDelay() {
        init(senderMock);
        when(senderMock.sendWithConfirm(any(), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(Mono.empty());

        Command<DummyMessage> command = new Command<>("test.command", "id", new DummyMessage());

        StepVerifier.create(asyncGateway.sendCommand(command, "target", 0L))
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(senderMock).sendWithConfirm(eq(command), eq("exchange"), eq("target"),
                headersCaptor.capture(), anyBoolean());
        assertThat(headersCaptor.getValue()).doesNotContainKey("rc-delay");
    }

    @Test
    void shouldSendCommandWithCustomDomainNoDelay() {
        init(senderMock);
        when(senderMock.sendWithConfirm(any(), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(Mono.empty());

        Command<DummyMessage> command = new Command<>("test.command", "id", new DummyMessage());

        StepVerifier.create(asyncGateway.sendCommand(command, "target", "customDomain"))
                .verifyComplete();

        verify(senderMock)
                .sendWithConfirm(eq(command), eq("exchange"), eq("target"), anyMap(), anyBoolean());
    }

    @Test
    void shouldSendCommandWithCustomDomainAndDelay() {
        init(senderMock);
        when(senderMock.sendWithConfirm(any(), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(Mono.empty());

        Command<DummyMessage> command = new Command<>("test.command", "id", new DummyMessage());

        StepVerifier.create(asyncGateway.sendCommand(
                        command, "target", 3000L, "customDomain"
                ))
                .verifyComplete();

        ArgumentCaptor<String> routingKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(senderMock).sendWithConfirm(eq(command), eq("exchange"), routingKeyCaptor.capture(),
                headersCaptor.capture(), anyBoolean());
        assertThat(routingKeyCaptor.getValue()).isEqualTo("target-delayed");
        assertThat(headersCaptor.getValue()).containsEntry("rc-delay", "3000");
    }

    @Test
    void shouldSendCommandsInBatch() {
        init(senderMock);
        when(senderMock.sendWithConfirmBatch(any(), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(Flux.empty());

        Flux<Command<DummyMessage>> commands = Flux.just(
                new Command<>("cmd1", "id1", new DummyMessage()),
                new Command<>("cmd2", "id2", new DummyMessage())
        );

        StepVerifier.create(asyncGateway.sendCommands(commands, "target"))
                .verifyComplete();

        verify(senderMock)
                .sendWithConfirmBatch(any(), eq("exchange"), eq("target"), anyMap(), anyBoolean());
    }

    @Test
    void shouldRequestReplyWithCustomDomain() {
        senderMock();
        mockReply();

        AsyncQuery<DummyMessage> query = new AsyncQuery<>("my.query", new DummyMessage());

        StepVerifier.create(asyncGateway.requestReply(
                        query, "target", DummyMessage.class, "customDomain")
                )
                .assertNext(res -> assertThat(res.getName()).startsWith("Daniel"))
                .verifyComplete();

        verify(senderMock)
                .sendNoConfirm(eq(query), eq("exchange"), eq("target.query"), anyMap(), anyBoolean());
    }

    private void senderMock() {
        init(senderMock);
        when(senderMock.sendNoConfirm(any(), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(Mono.empty());
    }

    private void mockReply() {
        Message message = mock(Message.class);
        ObjectMapper mapper = new ObjectMapper();
        when(message.getBody()).thenReturn(mapper.writeValueAsString(new DummyMessage()).getBytes());
        final Sinks.One<Message> processor = Sinks.one();
        processor.tryEmitValue(message);
        when(router.register(anyString())).thenReturn(processor.asMono());
    }

    private ReactiveMessageSender getReactiveMessageSender() {
        Sender sender = new StubSender();
        return new ReactiveMessageSender(
                sender, "sourceApplication", converter, null, true,
                unroutableMessageNotifier
        );
    }

    private Flux<Command<DummyMessage>> createMessagesHot(int count) {
        final List<Command<DummyMessage>> commands = IntStream.range(0, count)
                .mapToObj(value -> new Command<>(
                        "app.command.test", UUID.randomUUID().toString(), new DummyMessage()
                ))
                .toList();
        return Flux.fromIterable(commands);
    }

    static class StubSender extends Sender {

        @Override
        public Mono<Void> send(Publisher<OutboundMessage> messages) {
            return Flux.from(messages).then();
        }

        @Override
        public <OMSG extends OutboundMessage> Flux<OutboundMessageResult<OMSG>> sendWithTypedPublishConfirms(
                Publisher<OMSG> messages, SendOptions options) {
            return Flux.from(messages).map(omsg -> new OutboundMessageResult<>(omsg, true));
        }

        @Override
        @SuppressWarnings("rawtypes")
        public Flux<OutboundMessageResult> sendWithPublishConfirms(Publisher<OutboundMessage> messages) {
            return Flux.from(messages).map(omsg -> new OutboundMessageResult<>(omsg, true));
        }
    }

    @Data
    static class DummyMessage {
        private String name = "Daniel" + ThreadLocalRandom.current().nextLong();
        private Long age = ThreadLocalRandom.current().nextLong();
        private String field1 = "Field Data " + ThreadLocalRandom.current().nextLong();
        private String field2 = "Field Data " + ThreadLocalRandom.current().nextLong();
        private String field3 = "Field Data " + ThreadLocalRandom.current().nextLong();
        private String field4 = "Field Data " + ThreadLocalRandom.current().nextLong();
    }
}

