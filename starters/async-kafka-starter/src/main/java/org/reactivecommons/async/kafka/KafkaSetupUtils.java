package org.reactivecommons.async.kafka;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.reactivecommons.async.commons.DLQDiscardNotifier;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.SendLimits;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.kafka.config.KafkaReceiverOptionsCustomizer;
import org.reactivecommons.async.kafka.config.KafkaSenderOptionsCustomizer;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.config.spring.KafkaPropertiesBase;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.util.StringUtils;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;

import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class KafkaSetupUtils {

    /**
     * How long closing a consumer (its final commit) or the producer (the records it still holds) may take: without
     * a limit, an unreachable broker would hold the shutdown of the application.
     */
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The connection properties that have no effect, in the order they are reported, each with when it counts as
     * configured.
     */
    private static final List<IgnoredProperty> IGNORED_PROPERTIES = List.of(
            new IgnoredProperty("consumer.enable-auto-commit",
                    props -> Boolean.TRUE.equals(props.getConsumer().getEnableAutoCommit())),
            new IgnoredProperty("consumer.auto-commit-interval",
                    props -> props.getConsumer().getAutoCommitInterval() != null),
            // Read by the KafkaAdmin of Spring for Apache Kafka, which is not registered: the application starts anyway
            new IgnoredProperty("admin.fail-fast", props -> props.getAdmin().isFailFast()),
            new IgnoredProperty("listener.*",
                    props -> differsFromDefault(props.getListener(), new KafkaPropertiesBase.Listener())),
            new IgnoredProperty("template.*",
                    props -> differsFromDefault(props.getTemplate(), new KafkaPropertiesBase.Template())),
            new IgnoredProperty("retry.*",
                    props -> differsFromDefault(props.getRetry(), new KafkaPropertiesBase.Retry())),
            new IgnoredProperty("streams.*",
                    props -> differsFromDefault(props.getStreams(), new KafkaPropertiesBase.Streams())),
            new IgnoredProperty("jaas.*",
                    props -> differsFromDefault(props.getJaas(), new KafkaPropertiesBase.Jaas())));

    /**
     * The discarded messages are published with the headers of where they came from ({@link KafkaDlqHeaders}), which
     * the event bus takes from the listener that discards them.
     */
    public static DiscardNotifier createDiscardNotifier(ReactiveMessageSender sender, MessageConverter converter) {
        return new DLQDiscardNotifier(new KafkaDomainEventBus(sender, converter), converter);
    }

    public static ReactiveMessageSender createMessageSender(AsyncKafkaProps config,
                                                            MessageConverter converter,
                                                            TopologyCreator topologyCreator) {
        return createMessageSender(config, converter, topologyCreator, NoOpSchemaValidator.INSTANCE);
    }

    /**
     * Without SSL bundles, customizers nor metrics: see
     * {@link #createMessageSender(AsyncKafkaProps, MessageConverter, TopologyCreator, SchemaValidator,
     * KafkaClientSettings)}.
     */
    public static ReactiveMessageSender createMessageSender(AsyncKafkaProps config,
                                                            MessageConverter converter,
                                                            TopologyCreator topologyCreator,
                                                            SchemaValidator schemaValidator) {
        return createMessageSender(config, converter, topologyCreator, schemaValidator,
                KafkaClientSettings.defaults(DEFAULT_DOMAIN, config));
    }

    /**
     * The producer is created on the first send, and again after a producer that could not be created or failed for
     * good: each time with the same options. The properties of the domain are not changed.
     *
     * @throws org.apache.kafka.common.config.ConfigException when the Kafka producer rejects a property
     */
    public static ReactiveMessageSender createMessageSender(AsyncKafkaProps config,
                                                            MessageConverter converter,
                                                            TopologyCreator topologyCreator,
                                                            SchemaValidator schemaValidator,
                                                            KafkaClientSettings settings) {
        KafkaProperties props = config.getConnectionProperties();
        Duration sendTimeout = config.getSendTimeout() != null
                ? config.getSendTimeout()
                : ReactiveMessageSender.DEFAULT_SEND_TIMEOUT;
        int maxPendingSends = config.getMaxPendingSends() != null
                ? config.getMaxPendingSends()
                : ReactiveMessageSender.DEFAULT_MAX_PENDING_SENDS;
        SenderOptions<String, byte[]> options = createSenderOptions(props, sendTimeout, settings);
        return new ReactiveMessageSender(() -> KafkaSender.create(options), converter, topologyCreator,
                schemaValidator, new SendLimits(sendTimeout, maxPendingSends));
    }

    static SenderOptions<String, byte[]> createSenderOptions(KafkaProperties props) {
        return createSenderOptions(props, ReactiveMessageSender.DEFAULT_SEND_TIMEOUT);
    }

    static SenderOptions<String, byte[]> createSenderOptions(KafkaProperties props, Duration sendTimeout) {
        AsyncKafkaProps config = new AsyncKafkaProps();
        config.setConnectionProperties(props);
        return createSenderOptions(props, sendTimeout, KafkaClientSettings.defaults(DEFAULT_DOMAIN, config));
    }

    /**
     * {@code stopOnError(false)}: a failed record (e.g. the topic does not exist and the cluster does not auto-create
     * topics, so the producer times out waiting for its metadata) is reported as a failed {@code SenderResult}, which
     * fails only that send. With reactor-kafka's default ({@code true}) the whole send sequence is terminated on the
     * first failed record, failing every send waiting for its outcome and the producer with it. It is set again after
     * the customizers.
     * <p>
     * {@code max.block.ms}, when not configured, is the {@code sendTimeout}: the producer blocks up to it on each
     * record whose topic has no metadata (an unreachable broker, a topic that does not exist), and the records are
     * sent one after the other, so the client default (60 s) would hold every later send a minute per record.
     * <p>
     * The properties are checked once, as the Kafka producer reads them, after the customizers: a mistake in the
     * configured properties or in a customizer (e.g. a misspelled value of {@code acks}) fails the startup with the
     * error of the client, not every send once the application runs. The check writes the values in the log once,
     * because the Kafka producer has no public way to check them without logging them.
     */
    static SenderOptions<String, byte[]> createSenderOptions(KafkaProperties props, Duration sendTimeout,
                                                             KafkaClientSettings settings) {
        Map<String, Object> producerProperties = props.buildProducerProperties(settings.sslBundles());
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producerProperties.put(ProducerConfig.CLIENT_ID_CONFIG, StringUtils.hasText(props.getProducer().getClientId())
                ? props.getProducer().getClientId() : settings.producerClientId());
        producerProperties.putIfAbsent(ProducerConfig.MAX_BLOCK_MS_CONFIG, sendTimeout.toMillis());
        SenderOptions<String, byte[]> options = SenderOptions.<String, byte[]>create(producerProperties)
                .stopOnError(false)
                .closeTimeout(CLOSE_TIMEOUT)
                .producerListener(settings.metrics().producerListener());
        for (KafkaSenderOptionsCustomizer customizer : settings.senderCustomizers()) {
            options = Objects.requireNonNull(customizer.customize(settings.domain(), options),
                    "A KafkaSenderOptionsCustomizer returned null");
        }
        if (options.stopOnError()) {
            log.warn("Kafka domain {}: a KafkaSenderOptionsCustomizer set stopOnError(true), which is ignored: a "
                    + "single failed record would fail every send waiting for its outcome", settings.domain());
            options = options.stopOnError(false);
        }
        new ProducerConfig(options.producerProperties());
        return options;
    }

    // Receiver

    /**
     * Without SSL bundles, customizers nor metrics: see
     * {@link #createMessageListener(AsyncKafkaProps, SchemaValidator, KafkaClientSettings)}.
     *
     * @throws org.apache.kafka.common.config.ConfigException when the Kafka consumer rejects a property
     */
    public static ReactiveMessageListener createMessageListener(AsyncKafkaProps config,
                                                                SchemaValidator schemaValidator) {
        return createMessageListener(config, schemaValidator, KafkaClientSettings.defaults(DEFAULT_DOMAIN, config));
    }

    /**
     * The properties of the domain are not changed.
     *
     * @throws org.apache.kafka.common.config.ConfigException when the Kafka consumer rejects a property
     */
    public static ReactiveMessageListener createMessageListener(AsyncKafkaProps config,
                                                                SchemaValidator schemaValidator,
                                                                KafkaClientSettings settings) {
        return new ReactiveMessageListener(createReceiverOptions(config.getConnectionProperties(), settings),
                schemaValidator, settings.metrics().listenerMetrics());
    }

    static ReceiverOptions<String, byte[]> createReceiverOptions(KafkaProperties props) {
        AsyncKafkaProps config = new AsyncKafkaProps();
        config.setConnectionProperties(props);
        return createReceiverOptions(props, KafkaClientSettings.defaults(DEFAULT_DOMAIN, config));
    }

    /**
     * The properties are checked once, as the Kafka consumer reads them, after the customizers: a mistake in the
     * configured properties or in a customizer (e.g. a misspelled value of {@code auto.offset.reset}) fails the
     * startup with the error of the client, instead of failing every subscription of the listeners once the
     * application runs. The check does not write the values in the log: the Kafka consumer does when it is created.
     * <p>
     * {@code enable.auto.commit} is set to {@code false} again after the customizers: an offset is only committed
     * once its message was handled.
     */
    static ReceiverOptions<String, byte[]> createReceiverOptions(KafkaProperties props,
                                                                 KafkaClientSettings settings) {
        Map<String, Object> consumerProperties = props.buildConsumerProperties(settings.sslBundles());
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        consumerProperties.put(ConsumerConfig.CLIENT_ID_CONFIG, StringUtils.hasText(props.getConsumer().getClientId())
                ? props.getConsumer().getClientId() : settings.consumerClientId());
        ReceiverOptions<String, byte[]> options = ReceiverOptions.<String, byte[]>create(consumerProperties)
                .closeTimeout(CLOSE_TIMEOUT)
                .consumerListener(settings.metrics().consumerListener());
        for (KafkaReceiverOptionsCustomizer customizer : settings.receiverCustomizers()) {
            options = Objects.requireNonNull(customizer.customize(settings.domain(), options),
                    "A KafkaReceiverOptionsCustomizer returned null");
        }
        Object autoCommit = options.consumerProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG);
        if (autoCommit != null && Boolean.parseBoolean(autoCommit.toString())) {
            log.warn("Kafka domain {}: a KafkaReceiverOptionsCustomizer enabled {}, which is ignored: an offset is "
                    + "only committed once its message was handled", settings.domain(),
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG);
        }
        options = options.consumerProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        new UnloggedConsumerConfig(options.consumerProperties());
        return options;
    }

    // Shared

    /**
     * Without SSL bundles: see {@link #createTopologyCreator(AsyncKafkaProps, KafkaCustomizations,
     * KafkaClientSettings)}.
     */
    public static TopologyCreator createTopologyCreator(AsyncKafkaProps config, KafkaCustomizations customizations) {
        return createTopologyCreator(config, customizations, KafkaClientSettings.defaults(DEFAULT_DOMAIN, config));
    }

    /**
     * Nothing is asked to the cluster: the application starts even when the broker cannot be reached.
     */
    public static TopologyCreator createTopologyCreator(AsyncKafkaProps config, KafkaCustomizations customizations,
                                                        KafkaClientSettings settings) {
        return new TopologyCreator(createAdminClient(config.getConnectionProperties(), settings), customizations,
                config.getCheckExistingTopics());
    }

    /**
     * The admin client of a domain, shared by its topology creator and its health check. Creating it does not
     * connect to the cluster.
     */
    public static AdminClient createAdminClient(KafkaProperties props, KafkaClientSettings settings) {
        Map<String, Object> adminProperties = props.buildAdminProperties(settings.sslBundles());
        adminProperties.put(AdminClientConfig.CLIENT_ID_CONFIG, StringUtils.hasText(props.getAdmin().getClientId())
                ? props.getAdmin().getClientId() : settings.adminClientId());
        return AdminClient.create(adminProperties);
    }

    /**
     * The connection properties that are bound but have no effect, because reactor-kafka or the library does not use
     * them, by their name relative to {@code connectionProperties}. Only their names: their values may be secrets.
     */
    public static List<String> ignoredProperties(KafkaProperties props) {
        return IGNORED_PROPERTIES.stream()
                .filter(property -> property.configured().test(props))
                .map(IgnoredProperty::name)
                .toList();
    }

    /**
     * Compares the properties of a section with those of a new one, going into its nested sections. Only their getters
     * are called, never those of the values (e.g. a {@code Resource} is not read).
     */
    private static boolean differsFromDefault(Object configured, Object defaults) {
        BeanWrapper actual = new BeanWrapperImpl(configured);
        BeanWrapper expected = new BeanWrapperImpl(defaults);
        for (PropertyDescriptor property : actual.getPropertyDescriptors()) {
            String name = property.getName();
            if (property.getReadMethod() == null || "class".equals(name)) {
                continue;
            }
            Object value = actual.getPropertyValue(name);
            Object defaultValue = expected.getPropertyValue(name);
            boolean nested = value != null && value.getClass().getName()
                    .startsWith(KafkaPropertiesBase.class.getName() + "$");
            if (nested ? differsFromDefault(value, defaultValue) : !Objects.equals(value, defaultValue)) {
                return true;
            }
        }
        return false;
    }

    // Utilities

    /**
     * Reads {@code KEY=VALUE} lines into the common {@code properties} of a {@link KafkaProperties}. Blank lines and
     * lines starting with {@code #} are skipped, and each line is stripped, so Windows line endings are accepted. The
     * value is everything after the first {@code =}.
     *
     * @throws IllegalArgumentException when a line has no {@code =} or no key; the message tells the number of the
     *                                  line, not its content, which may be a secret
     */
    public static KafkaProperties readPropsFromDotEnv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        KafkaProperties props = new KafkaProperties();
        Map<String, String> properties = props.getProperties();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Invalid line " + (i + 1) + " of " + path.getFileName()
                        + ": expected KEY=VALUE");
            }
            properties.put(line.substring(0, separator).strip(), line.substring(separator + 1));
        }
        return props;
    }

    /**
     * @param name       the name of the property relative to {@code connectionProperties}
     * @param configured whether the property is configured, so it would be expected to have an effect
     */
    private record IgnoredProperty(String name, Predicate<KafkaProperties> configured) {
    }

    /**
     * Checks the properties of a consumer as {@link ConsumerConfig} does, without writing their values in the log:
     * the constructor that does not log them is only open to subclasses.
     */
    private static final class UnloggedConsumerConfig extends ConsumerConfig {

        private UnloggedConsumerConfig(Map<String, Object> properties) {
            super(properties, false);
        }
    }
}
