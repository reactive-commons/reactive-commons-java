package org.reactivecommons.async.impl;

import lombok.Data;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.config.BrokerConfig;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.impl.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.impl.converters.json.JacksonMessageConverter;
import org.reactivecommons.async.impl.reply.ReactiveReplyRouter;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.Sender;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

public class RabbitDirectAsyncGatewayTest {

    private final BrokerConfig config = new BrokerConfig();

    @Mock
    private ReactiveReplyRouter router;

    @Mock
    private MessageConverter converter;

    private RabbitDirectAsyncGateway asyncGateway;
    private final Semaphore semaphore = new Semaphore(0);

    @Before
    public void init() {
        ReactiveMessageSender sender = getReactiveMessageSender();
        asyncGateway = new RabbitDirectAsyncGateway(config, router, sender, "exchange", converter);
    }

    @Test
    public void shouldSendInOptimalTime() throws InterruptedException {
        int messageCount = 40000;
        final Flux<Command<DummyMessage>> messages = createMessagesHot(messageCount);
        final Flux<Void> target = messages.flatMap(dummyMessageCommand -> asyncGateway.sendCommand(dummyMessageCommand, "testTarget")
            .doOnSuccess(aVoid -> semaphore.release()));


        final long init = System.currentTimeMillis();
        target.subscribe();
        semaphore.acquire(messageCount);
        final long end = System.currentTimeMillis();

        final long total = end - init;
        final double microsPerMessage =  ((total+0.0)/messageCount)*1000;
        System.out.println("Message count: " + messageCount);
        System.out.println("Total Execution Time: " + total + "ms");
        System.out.println("Microseconds per message: " + microsPerMessage + "us");
        Assertions.assertThat(microsPerMessage).isLessThan(50);
    }

    private ReactiveMessageSender getReactiveMessageSender() {
        MessageConverter messageConverter = new JacksonMessageConverter(new DefaultObjectMapperSupplier().get());
        Sender sender = new StubSender();
        ReactiveMessageSender reactiveSender = new ReactiveMessageSender(sender, "sourceApplication", messageConverter, null);
        return reactiveSender;
    }

    static class StubSender extends Sender {

        @Override
        public <OMSG extends OutboundMessage> Flux<OutboundMessageResult<OMSG>> sendWithTypedPublishConfirms(Publisher<OMSG> messages) {
            return Flux.from(messages).map(omsg -> new OutboundMessageResult<>(omsg, true));
        }
    }


    private Flux<Command<DummyMessage>> createMessagesHot(int count) {
        final List<Command<DummyMessage>> commands = IntStream.range(0, count).mapToObj(value -> new Command<>("app.command.test", UUID.randomUUID().toString(), new DummyMessage())).collect(Collectors.toList());
        return Flux.fromIterable(commands);
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

