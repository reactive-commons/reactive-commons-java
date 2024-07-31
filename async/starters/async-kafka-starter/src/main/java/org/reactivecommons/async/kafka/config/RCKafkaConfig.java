package org.reactivecommons.async.kafka.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.DLQDiscardNotifier;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.commons.converters.json.ObjectMapperSupplier;
import org.reactivecommons.async.kafka.KafkaDomainEventBus;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.props.RCKafkaProps;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG;

@Configuration
public class RCKafkaConfig {
    // Sender
    @Bean
    @ConditionalOnMissingBean(DomainEventBus.class)
    public DomainEventBus kafkaDomainEventBus(ReactiveMessageSender sender) {
        return new KafkaDomainEventBus(sender);
    }

    @Bean
    @ConditionalOnMissingBean(ReactiveMessageSender.class)
    public ReactiveMessageSender kafkaReactiveMessageSender(KafkaSender<String, byte[]> kafkaSender,
                                                            MessageConverter converter, TopologyCreator topologyCreator) {
        return new ReactiveMessageSender(kafkaSender, converter, topologyCreator);
    }

    @Bean
    @ConditionalOnMissingBean(KafkaSender.class)
    public KafkaSender<String, byte[]> kafkaSender(RCKafkaProps props, @Value("${spring.application.name}") String clientId) {
        props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        SenderOptions<String, byte[]> senderOptions = SenderOptions.create(props);
        return KafkaSender.create(senderOptions);
    }

    // Receiver

    @Bean
    @ConditionalOnMissingBean(ReactiveMessageListener.class)
    public ReactiveMessageListener kafkaReactiveMessageListener(ReceiverOptions<String, byte[]> receiverOptions) {
        return new ReactiveMessageListener(receiverOptions);
    }

    @Bean
    @ConditionalOnMissingBean(ReceiverOptions.class)
    public ReceiverOptions<String, byte[]> kafkaReceiverOptions(RCKafkaProps props) {
        props.put(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return ReceiverOptions.create(props);
    }

    // Shared

    @Bean
    @ConditionalOnMissingBean(TopologyCreator.class)
    public TopologyCreator kafkaTopologyCreator(RCKafkaProps props, KafkaCustomizations customizations) {
        AdminClient adminClient = AdminClient.create(props);
        return new TopologyCreator(adminClient, customizations);
    }

    @Bean
    @ConditionalOnMissingBean(KafkaCustomizations.class)
    public KafkaCustomizations defaultKafkaCustomizations() {
        return new KafkaCustomizations();
    }

    @Bean
    @ConditionalOnMissingBean(RCKafkaProps.class)
    public RCKafkaProps kafkaProps() throws IOException {
        String env = Files.readString(Path.of(".kafka-env"));
        String[] split = env.split("\n");
        RCKafkaProps props = new RCKafkaProps();
        for (String s : split) {
            if (s.startsWith("#")) {
                continue;
            }
            String[] split1 = s.split("=", 2);
            props.put(split1[0], split1[1]);
        }
        return props;
    }

    @Bean
    @ConditionalOnMissingBean(MessageConverter.class)
    public MessageConverter kafkaJacksonMessageConverter(ObjectMapperSupplier objectMapperSupplier) {
        return new KafkaJacksonMessageConverter(objectMapperSupplier.get());
    }

    @Bean
    public DiscardNotifier kafkaDiscardNotifier(DomainEventBus domainEventBus, MessageConverter messageConverter) {
        return new DLQDiscardNotifier(domainEventBus, messageConverter);
    }

    @Bean
    public ObjectMapperSupplier defaultObjectMapperSupplier() {
        return new DefaultObjectMapperSupplier();
    }

    public static String jassConfig(String username, String password) {
        return String.format("org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";", username, password);
    }
}
