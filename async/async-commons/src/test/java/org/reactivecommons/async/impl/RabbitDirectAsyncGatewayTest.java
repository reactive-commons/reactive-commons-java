package org.reactivecommons.async.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.From;
import org.reactivecommons.async.parent.communications.Message;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.parent.config.BrokerConfig;
import org.reactivecommons.async.parent.converters.MessageConverter;
import org.reactivecommons.async.parent.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.parent.converters.json.JacksonMessageConverter;
import org.reactivecommons.async.parent.reply.ReactiveReplyRouter;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.UnicastProcessor;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.Sender;
import reactor.test.StepVerifier;
import reactor.util.concurrent.Queues;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.reactivecommons.async.parent.Headers.*;


@ExtendWith(MockitoExtension.class)
public class RabbitDirectAsyncGatewayTest {

    private final BrokerConfig config = new BrokerConfig();
    private final Semaphore semaphore = new Semaphore(0);
    private final MessageConverter converter = new JacksonMessageConverter(new DefaultObjectMapperSupplier().get());
    @Mock
    private ReactiveReplyRouter router;
    @Mock
    private ReactiveMessageSender senderMock;
    private RabbitDirectAsyncGateway asyncGateway;

    public void init(ReactiveMessageSender sender) {
        asyncGateway = new RabbitDirectAsyncGateway(config, router, sender, "exchange", converter);
    }

    @Test
    public void shouldReleaseRouterResourcesOnTimeout() {
        BrokerConfig config = new BrokerConfig(false, false, false, Duration.ofSeconds(1));
        asyncGateway = new RabbitDirectAsyncGateway(config, router, senderMock, "ex", converter);
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
    public void shouldSendInOptimalTime() throws InterruptedException {
        init(getReactiveMessageSender());

        int messageCount = 40000;
        final Flux<Command<DummyMessage>> messages = createMessagesHot(messageCount);
        final Flux<Void> target =
                messages.flatMap(dummyMessageCommand -> asyncGateway.sendCommand(dummyMessageCommand, "testTarget")
                        .doOnSuccess(aVoid -> semaphore.release()));

        final long init = System.currentTimeMillis();
        target.subscribe();
        semaphore.acquire(messageCount);
        final long end = System.currentTimeMillis();

        final long total = end - init;
        final double microsPerMessage = ((total + 0.0) / messageCount) * 1000;
        System.out.println("Message count: " + messageCount);
        System.out.println("Total Execution Time: " + total + "ms");
        System.out.println("Microseconds per message: " + microsPerMessage + "us");
        assertThat(microsPerMessage).isLessThan(150);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldReplyQuery() {
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
                .sendNoConfirm(eq(response), eq("globalReply"), eq("replyId"), headersCaptor.capture(), anyBoolean());
        assertThat(headersCaptor.getValue().get(CORRELATION_ID)).isEqualTo("correlationId");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldReplyQueryWithout() {
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
                .sendNoConfirm(eq(null), eq("globalReply"), eq("replyId"), headersCaptor.capture(), anyBoolean());
        assertThat(headersCaptor.getValue().get(CORRELATION_ID)).isEqualTo("correlationId");
        assertThat(headersCaptor.getValue().get(COMPLETION_ONLY_SIGNAL)).isEqualTo(Boolean.TRUE.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldHandleRequestReply() throws JsonProcessingException {
        // Arrange
        senderMock();
        mockReply();

        String queryName = "my.query";
        String targetName = "app-target";
        AsyncQuery<DummyMessage> query = new AsyncQuery<>(queryName, new DummyMessage());
        // Act
        Mono<DummyMessage> result = asyncGateway.requestReply(query, targetName, DummyMessage.class);
        // Assert
        StepVerifier.create(result)
                .assertNext(res -> assertThat(res.getName()).startsWith("Daniel"))
                .verifyComplete();
        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(senderMock, times(1))
                .sendNoConfirm(eq(query), eq("exchange"), eq("app-target.query"), headersCaptor.capture(),
                        anyBoolean());
        assertThat(headersCaptor.getValue().get(REPLY_ID).toString().length()).isEqualTo(32);
        assertThat(headersCaptor.getValue().get(CORRELATION_ID).toString().length()).isEqualTo(32);
    }

    private void senderMock() {
        init(senderMock);
        when(senderMock.sendNoConfirm(any(), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(Mono.empty());
    }

    private void mockReply() throws JsonProcessingException {
        Message message = mock(Message.class);
        ObjectMapper mapper = new ObjectMapper();
        when(message.getBody()).thenReturn(mapper.writeValueAsString(new DummyMessage()).getBytes());
        final UnicastProcessor<Message> processor = UnicastProcessor.create(Queues.<Message>one().get());
        processor.onNext(message);
        processor.onComplete();
        when(router.register(anyString())).thenReturn(processor.singleOrEmpty());
    }

    private ReactiveMessageSender getReactiveMessageSender() {
        Sender sender = new StubSender();
        return new ReactiveMessageSender(sender, "sourceApplication", converter, null);
    }

    private Flux<Command<DummyMessage>> createMessagesHot(int count) {
        final List<Command<DummyMessage>> commands = IntStream.range(0, count).mapToObj(value -> new Command<>("app" +
                ".command.test", UUID.randomUUID().toString(), new DummyMessage())).collect(Collectors.toList());
        return Flux.fromIterable(commands);
    }

    static class StubSender extends Sender {

        @Override
        public <OMSG extends OutboundMessage> Flux<OutboundMessageResult<OMSG>> sendWithTypedPublishConfirms(Publisher<OMSG> messages) {
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

