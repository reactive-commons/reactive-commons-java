package org.reactivecommons.async.kafka.communications;

import lombok.SneakyThrows;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.kafka.sender.SenderResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class ReactiveMessageSender {
    private final ConcurrentHashMap<String, MonoSink<Void>> confirmations = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<FluxSink<SenderRecord<String, byte[], String>>> fluxSinks =
            new CopyOnWriteArrayList<>();
    private final AtomicLong counter = new AtomicLong();

    private final ExecutorService executorServiceConfirm = Executors.newFixedThreadPool(13, r ->
            new Thread(r, "KMessageSender1-" + counter.getAndIncrement())
    );
    private final ExecutorService executorServiceEmit = Executors.newFixedThreadPool(13, r ->
            new Thread(r, "KMessageSender2-" + counter.getAndIncrement())
    );

    private static final int SENDER_COUNT = 4;

    private final MessageConverter messageConverter;
    private final TopologyCreator topologyCreator;

    public ReactiveMessageSender(KafkaSender<String, byte[]> sender, MessageConverter messageConverter,
                                 TopologyCreator topologyCreator) {
        this.messageConverter = messageConverter;
        this.topologyCreator = topologyCreator;
        for (int i = 0; i < SENDER_COUNT; ++i) {
            Flux<SenderRecord<String, byte[], String>> source = Flux.create(fluxSinks::add);
            sender.send(source)
                    .doOnNext(this::confirm)
                    .subscribe();
        }
    }

    public <V> Mono<Void> send(V message) {
        return Mono.create(sink -> {
            SenderRecord<String, byte[], String> senderRecord = createRecord(message);
            confirmations.put(senderRecord.key(), sink);
            executorServiceEmit.submit(() -> fluxSinks.get((int) (System.currentTimeMillis() % SENDER_COUNT))
                    .next(senderRecord));
        });
    }

    private void confirm(SenderResult<String> result) {
        executorServiceConfirm.submit(() -> {
            MonoSink<Void> sink = confirmations.remove(result.correlationMetadata());
            if (sink != null) {
                if (result.exception() != null) {
                    sink.error(result.exception());
                } else {
                    sink.success();
                }
            }
        });
    }

    private <V> SenderRecord<String, byte[], String> createRecord(V object) {
        KafkaMessage message = (KafkaMessage) messageConverter.toMessage(object);
        ProducerRecord<String, byte[]> producerRecord = createProducerRecord(message);
        return SenderRecord.create(producerRecord, message.getProperties().getKey()); // TODO: Review for Request-Reply
    }

    @SneakyThrows
    private ProducerRecord<String, byte[]> createProducerRecord(KafkaMessage message) {
        topologyCreator.checkTopic(message.getProperties().getTopic());

        List<Header> headers = message.getProperties().getHeaders().entrySet().stream()
                .map(entry -> new RecordHeader(entry.getKey(), entry.getValue()
                        .toString().getBytes(StandardCharsets.UTF_8)))
                .collect(Collectors.toList());

        return new ProducerRecord<>(message.getProperties().getTopic(), null,
                message.getProperties().getKey(), message.getBody(), headers);
    }
}
