package org.reactivecommons.async.kafka;

import lombok.experimental.UtilityClass;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.reactivecommons.async.commons.DLQDiscardNotifier;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.springframework.boot.ssl.SslBundles;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@UtilityClass
public class KafkaSetupUtils {

    public static DiscardNotifier createDiscardNotifier(ReactiveMessageSender sender, MessageConverter converter) {
        return new DLQDiscardNotifier(new KafkaDomainEventBus(sender), converter);
    }


    public static ReactiveMessageSender createMessageSender(AsyncKafkaProps config,
                                                            MessageConverter converter,
                                                            TopologyCreator topologyCreator,
                                                            SslBundles sslBundles) {
        KafkaProperties props = config.getConnectionProperties();
        props.setClientId(config.getAppName()); // CLIENT_ID_CONFIG
        props.getProducer().setKeySerializer(StringSerializer.class); // KEY_SERIALIZER_CLASS_CONFIG;
        props.getProducer().setValueSerializer(ByteArraySerializer.class); // VALUE_SERIALIZER_CLASS_CONFIG
        SenderOptions<String, byte[]> senderOptions = SenderOptions.create(props.buildProducerProperties(sslBundles));
        KafkaSender<String, byte[]> kafkaSender = KafkaSender.create(senderOptions);
        return new ReactiveMessageSender(kafkaSender, converter, topologyCreator);
    }

    // Receiver

    public static ReactiveMessageListener createMessageListener(AsyncKafkaProps config, SslBundles sslBundles) {
        KafkaProperties props = config.getConnectionProperties();
        props.getConsumer().setKeyDeserializer(StringDeserializer.class); // KEY_DESERIALIZER_CLASS_CONFIG
        props.getConsumer().setValueDeserializer(ByteArrayDeserializer.class); // VALUE_DESERIALIZER_CLASS_CONFIG
        ReceiverOptions<String, byte[]> receiverOptions = ReceiverOptions.create(props.buildConsumerProperties(sslBundles));
        return new ReactiveMessageListener(receiverOptions);
    }

    // Shared
    public static TopologyCreator createTopologyCreator(AsyncKafkaProps config, KafkaCustomizations customizations,
                                                        SslBundles sslBundles) {
        AdminClient adminClient = AdminClient.create(config.getConnectionProperties().buildAdminProperties(sslBundles));
        return new TopologyCreator(adminClient, customizations, config.getCheckExistingTopics());
    }

    // Utilities

    public static KafkaProperties readPropsFromDotEnv(Path path) throws IOException {
        String env = Files.readString(path);
        String[] split = env.split("\n");
        KafkaProperties props = new KafkaProperties();
        Map<String, String> properties = props.getProperties();
        for (String s : split) {
            if (s.startsWith("#")) {
                continue;
            }
            String[] split1 = s.split("=", 2);
            properties.put(split1[0], split1[1]);
        }
        return props;
    }

    public static String jassConfig(String username, String password) {
        return String.format("org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";", username, password);
    }
}
