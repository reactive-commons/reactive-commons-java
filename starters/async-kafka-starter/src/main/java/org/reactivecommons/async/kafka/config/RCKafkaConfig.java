package org.reactivecommons.async.kafka.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.commons.DLQDiscardNotifier;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.HandlerResolverBuilder;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.commons.converters.json.ObjectMapperSupplier;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.ext.DefaultCustomReporter;
import org.reactivecommons.async.kafka.KafkaDomainEventBus;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomainProperties;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.CLIENT_ID_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Configuration
@EnableConfigurationProperties({KafkaPropertiesAutoConfig.class, AsyncKafkaPropsDomainProperties.class})
@Import(AsyncKafkaPropsDomain.class) // RabbitHealthConfig.class
public class RCKafkaConfig {

    @Bean
    public ConnectionManager kafkaConnectionManager(AsyncKafkaPropsDomain props,
                                                    MessageConverter converter,
                                                    KafkaCustomizations customizations) {
        ConnectionManager connectionManager = new ConnectionManager();
        props.forEach((domain, properties) -> {
            TopologyCreator creator = createTopologyCreator(properties, customizations);
            ReactiveMessageSender sender = createMessageSender(properties, converter, creator);
            ReactiveMessageListener listener = createMessageListener(properties);
            connectionManager.addDomain(domain, listener, sender, creator);

            ReactiveMessageSender appDomainSender = connectionManager.getSender(domain);
            DomainEventBus appDomainEventBus = new KafkaDomainEventBus(appDomainSender);
            DiscardNotifier notifier = new DLQDiscardNotifier(appDomainEventBus, converter);
            connectionManager.setDiscardNotifier(domain, notifier);
        });
        return connectionManager;
    }

    @Bean
    public DomainHandlers buildHandlers(AsyncKafkaPropsDomain props, ApplicationContext context,
                                        HandlerRegistry primaryRegistry, DefaultCommandHandler<?> commandHandler) {
        DomainHandlers handlers = new DomainHandlers();
        final Map<String, HandlerRegistry> registries = context.getBeansOfType(HandlerRegistry.class);
        if (!registries.containsValue(primaryRegistry)) {
            registries.put("primaryHandlerRegistry", primaryRegistry);
        }
        props.forEach((domain, properties) -> {
            HandlerResolver resolver = HandlerResolverBuilder.buildResolver(domain, registries, commandHandler);
            handlers.add(domain, resolver);
        });
        return handlers;
    }


    // Sender
    @Bean
    @ConditionalOnMissingBean(DomainEventBus.class)
    public DomainEventBus kafkaDomainEventBus(ConnectionManager manager) {
        return new KafkaDomainEventBus(manager.getSender(DEFAULT_DOMAIN));
    }

    private static ReactiveMessageSender createMessageSender(AsyncKafkaProps config,
                                                             MessageConverter converter,
                                                             TopologyCreator topologyCreator) {
        KafkaProperties props = config.getConnectionProperties();
        props.put(CLIENT_ID_CONFIG, config.getAppName());
        props.put(KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        SenderOptions<String, byte[]> senderOptions = SenderOptions.create(props);
        KafkaSender<String, byte[]> kafkaSender = KafkaSender.create(senderOptions);
        return new ReactiveMessageSender(kafkaSender, converter, topologyCreator);
    }

    // Receiver

    private static ReactiveMessageListener createMessageListener(AsyncKafkaProps config) {
        KafkaProperties props = config.getConnectionProperties();
        props.put(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        ReceiverOptions<String, byte[]> receiverOptions = ReceiverOptions.create(props);
        return new ReactiveMessageListener(receiverOptions);
    }

    // Shared
    private static TopologyCreator createTopologyCreator(AsyncKafkaProps config, KafkaCustomizations customizations) {
        AdminClient adminClient = AdminClient.create(config.getConnectionProperties());
        return new TopologyCreator(adminClient, customizations, config.getCheckExistingTopics());
    }

    @Bean
    @ConditionalOnMissingBean(KafkaCustomizations.class)
    public KafkaCustomizations defaultKafkaCustomizations() {
        return new KafkaCustomizations();
    }

    @Bean
    @ConditionalOnMissingBean(MessageConverter.class)
    public MessageConverter kafkaJacksonMessageConverter(ObjectMapperSupplier objectMapperSupplier) {
        return new KafkaJacksonMessageConverter(objectMapperSupplier.get());
    }

    @Bean
    @ConditionalOnMissingBean(DiscardNotifier.class)
    public DiscardNotifier kafkaDiscardNotifier(DomainEventBus domainEventBus, MessageConverter messageConverter) {
        return new DLQDiscardNotifier(domainEventBus, messageConverter);
    }

    @Bean
    @ConditionalOnMissingBean(ObjectMapperSupplier.class)
    public ObjectMapperSupplier defaultObjectMapperSupplier() {
        return new DefaultObjectMapperSupplier();
    }

    @Bean
    @ConditionalOnMissingBean(CustomReporter.class)
    public CustomReporter defaultKafkaCustomReporter() {
        return new DefaultCustomReporter();
    }

    @Bean
    @ConditionalOnMissingBean(AsyncKafkaPropsDomain.KafkaSecretFiller.class)
    public AsyncKafkaPropsDomain.KafkaSecretFiller defaultKafkaSecretFiller() {
        return (ignoredDomain, ignoredProps) -> {
        };
    }

    @Bean
    @ConditionalOnMissingBean(KafkaProperties.class)
    public KafkaProperties defaultKafkaProperties(KafkaPropertiesAutoConfig properties, ObjectMapperSupplier supplier) {
        return supplier.get().convertValue(properties, KafkaProperties.class);
    }

    @Bean
    @ConditionalOnMissingBean(DefaultCommandHandler.class)
    public DefaultCommandHandler<?> defaultCommandHandler() {
        return command -> Mono.empty();
    }

    // Utilities

    public static KafkaProperties readPropsFromDotEnv(Path path) throws IOException {
        String env = Files.readString(path);
        String[] split = env.split("\n");
        KafkaProperties props = new KafkaProperties();
        for (String s : split) {
            if (s.startsWith("#")) {
                continue;
            }
            String[] split1 = s.split("=", 2);
            props.put(split1[0], split1[1]);
        }
        return props;
    }

    public static String jassConfig(String username, String password) {
        return String.format("org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";", username, password);
    }
}
