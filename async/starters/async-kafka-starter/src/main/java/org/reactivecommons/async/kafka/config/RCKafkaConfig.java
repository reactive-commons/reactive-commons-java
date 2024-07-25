package org.reactivecommons.async.kafka.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.KafkaDomainEventBus;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.props.RCKafkaProps;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
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
    public DomainEventBus domainEventBus(ReactiveMessageSender sender) {
        return new KafkaDomainEventBus(sender);
    }

    @Bean
    public ReactiveMessageSender reactiveMessageSender(KafkaSender<String, byte[]> kafkaSender,
                                                       MessageConverter converter, TopologyCreator topologyCreator) {
        return new ReactiveMessageSender(kafkaSender, converter, topologyCreator);
    }

    @Bean
    public KafkaSender<String, byte[]> kafkaSender(RCKafkaProps props) {
//        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, secret.getBootstrapServers());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "sample-producer");
//        props.put(ProducerConfig.ACKS_CONFIG, "all");
//        props.put(SECURITY_PROTOCOL_CONFIG, SECURITY_PROTOCOL_SASL_SSL);
//        props.put(SASL_MECHANISM_CONFIG, SASL_MECHANISM_PLAIN);
//        props.put(SASL_JAAS_CONFIG, jassConfig(secret.getUsername(), secret.getPassword()));
        props.put(KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        SenderOptions<String, byte[]> senderOptions = SenderOptions.create(props);
        return KafkaSender.create(senderOptions);
    }

    // Receiver

    @Bean
    public ReactiveMessageListener reactiveMessageListener(ReceiverOptions<String, byte[]> receiverOptions) {
        return new ReactiveMessageListener(receiverOptions);
    }

    @Bean
    public ReceiverOptions<String, byte[]> receiverOptions(RCKafkaProps props) {
        props.put(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return ReceiverOptions.create(props);
    }

    // Shared

    @Bean
    public TopologyCreator topologyCreator(RCKafkaProps props) {
        AdminClient adminClient = AdminClient.create(props);
        return new TopologyCreator(adminClient, new KafkaCustomizations());
    }

    @Bean
    public RCKafkaProps kafkaProps() throws IOException {
        String env = Files.readString(Path.of("/Users/jcgalvis/projects/reactive-commons-java/.kafka-env"));
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
    public MessageConverter kafkaJacksonMessageConverter(ObjectMapper objectMapper) {
        return new KafkaJacksonMessageConverter(objectMapper);
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        return mapper;
    }

    public static String jassConfig(String username, String password) {
        return String.format("org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";", username, password);
    }
}
