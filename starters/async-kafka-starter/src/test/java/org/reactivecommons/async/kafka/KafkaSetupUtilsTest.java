package org.reactivecommons.async.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.kafka.config.KafkaReceiverOptionsCustomizer;
import org.reactivecommons.async.kafka.config.KafkaSenderOptionsCustomizer;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.metrics.KafkaDomainMetrics;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.SslBundleSslEngineFactory;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslStoreBundle;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.sender.SenderOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class KafkaSetupUtilsTest {

    @Test
    void shouldNotStopTheSendSequenceOnRecordErrors() {
        // A record that fails (e.g. topic missing with auto-creation disabled) must fail only its own send
        SenderOptions<String, byte[]> options = KafkaSetupUtils.createSenderOptions(new KafkaProperties());

        assertThat(options.stopOnError()).isFalse();
    }

    /**
     * A property the Kafka consumer rejects fails the startup with the error of the client, instead of failing every
     * subscription of the listener once the application runs.
     */
    @Test
    void shouldNotCreateTheListenerWithAConsumerPropertyTheClientRejects() {
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.getConnectionProperties().getConsumer().setAutoOffsetReset("lastest");

        assertThatThrownBy(() -> KafkaSetupUtils.createMessageListener(props, NoOpSchemaValidator.INSTANCE))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
    }

    /**
     * The properties of the {@code properties} map are text: {@code max.poll.records} is given to the consumer as it
     * is, which reads it as a number.
     */
    @Test
    void shouldGiveTheConsumerAMaxPollRecordsConfiguredAsText() {
        KafkaProperties props = new KafkaProperties();
        props.getProperties().put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10");

        ReceiverOptions<String, byte[]> options = KafkaSetupUtils.createReceiverOptions(props);

        assertThat(new ConsumerConfig(options.consumerProperties()).getInt(ConsumerConfig.MAX_POLL_RECORDS_CONFIG))
                .isEqualTo(10);
    }

    @Test
    void shouldNotCreateTheListenerWithAMaxPollRecordsThatIsNotANumber() {
        KafkaProperties props = new KafkaProperties();
        props.getProperties().put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "ten");

        assertThatThrownBy(() -> KafkaSetupUtils.createReceiverOptions(props))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(ConsumerConfig.MAX_POLL_RECORDS_CONFIG);
    }

    /**
     * Closing the consumer (its final commit) and the producer (the records it still holds) waits a bounded time, so
     * an unreachable broker cannot hold the shutdown of the application.
     */
    @Test
    void shouldBoundTheTimeTheConsumerAndTheProducerTakeToClose() {
        ReceiverOptions<String, byte[]> receiverOptions = KafkaSetupUtils.createReceiverOptions(new KafkaProperties());
        SenderOptions<String, byte[]> senderOptions = KafkaSetupUtils.createSenderOptions(new KafkaProperties());

        assertThat(receiverOptions.closeTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(senderOptions.closeTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    /**
     * KC-06 (producer): a property the Kafka producer rejects fails the creation of the sender with the error of the
     * client, before any producer is created, instead of failing every send once the application runs.
     */
    @Test
    void shouldNotCreateTheSenderWithAProducerPropertyTheClientRejects() {
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.setAppName("app");
        props.getConnectionProperties().getProducer().setAcks("everything");
        Set<String> producerThreadsBefore = producerThreads();

        assertThatThrownBy(() -> KafkaSetupUtils.createMessageSender(props, null, mock(TopologyCreator.class)))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(ProducerConfig.ACKS_CONFIG);
        assertThat(producerThreads()).as("producer threads").isEqualTo(producerThreadsBefore);
    }

    /**
     * KC-09: without {@code max.block.ms}, the producer blocks on a record without metadata up to the
     * {@code sendTimeout}, not the minute of the client default.
     */
    @Test
    void shouldBlockTheProducerUpToTheSendTimeoutWhenMaxBlockIsNotConfigured() {
        SenderOptions<String, byte[]> options = KafkaSetupUtils.createSenderOptions(new KafkaProperties(),
                Duration.ofSeconds(3));

        assertThat(options.producerProperties()).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000L);
    }

    @Test
    void shouldKeepTheConfiguredMaxBlock() {
        KafkaProperties props = new KafkaProperties();
        props.getProperties().put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "1500");

        SenderOptions<String, byte[]> options = KafkaSetupUtils.createSenderOptions(props, Duration.ofSeconds(3));

        assertThat(options.producerProperties()).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, "1500");
    }

    @Test
    void shouldDefaultTheSendTimeoutAndTheMaxPendingSends() {
        AsyncKafkaProps props = new AsyncKafkaProps();

        assertThat(props.getSendTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props.getMaxPendingSends()).isEqualTo(10_000);
    }

    /**
     * KC-30: blank lines, comments and Windows line endings are accepted.
     */
    @Test
    void shouldReadADotEnvWithBlankLinesCommentsAndWindowsLineEndings(@TempDir Path dir) throws IOException {
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                # kafka\r
                bootstrap.servers=broker:9092\r
                \r
                   \r
                  # indented comment\r
                sasl.jaas.config=module required user="a" password="b=c";\r
                """);

        KafkaProperties props = KafkaSetupUtils.readPropsFromDotEnv(env);

        assertThat(props.getProperties())
                .containsExactlyInAnyOrderEntriesOf(Map.of("bootstrap.servers", "broker:9092",
                        "sasl.jaas.config", "module required user=\"a\" password=\"b=c\";"));
    }

    /**
     * KC-30: a line without {@code =} fails with its number, without showing its content, which may be a secret.
     */
    @Test
    void shouldRejectALineWithoutEqualsTellingItsNumber(@TempDir Path dir) throws IOException {
        Path env = dir.resolve(".env");
        Files.writeString(env, "bootstrap.servers=broker:9092\n\ntopsecretvalue\n");

        assertThatThrownBy(() -> KafkaSetupUtils.readPropsFromDotEnv(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 3")
                .hasMessageNotContaining("topsecretvalue");
    }

    /**
     * KC-21: with {@code ssl.bundle} the consumer and the producer get the TLS settings of the bundle, as Spring Boot
     * applies them; they used to get nothing.
     */
    @Test
    void shouldApplyTheSslBundleToTheConsumerAndTheProducer() {
        DefaultSslBundleRegistry bundles = new DefaultSslBundleRegistry();
        SslBundle bundle = SslBundle.of(SslStoreBundle.NONE);
        bundles.registerBundle("kafka-tls", bundle);
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.getConnectionProperties().getSsl().setBundle("kafka-tls");
        KafkaClientSettings settings = new KafkaClientSettings("app", "svc-1", bundles, List.of(), List.of(),
                KafkaDomainMetrics.NONE);

        ReceiverOptions<String, byte[]> receiverOptions = KafkaSetupUtils.createReceiverOptions(
                props.getConnectionProperties(), settings);
        SenderOptions<String, byte[]> senderOptions = KafkaSetupUtils.createSenderOptions(
                props.getConnectionProperties(), Duration.ofSeconds(3), settings);

        for (Map<String, Object> properties : List.of(receiverOptions.consumerProperties(),
                senderOptions.producerProperties())) {
            assertThat(properties)
                    .containsEntry(SslConfigs.SSL_ENGINE_FACTORY_CLASS_CONFIG, SslBundleSslEngineFactory.class)
                    .containsEntry(SslBundle.class.getName(), bundle);
        }
    }

    @Test
    void shouldFailWhenTheSslBundleDoesNotExist() {
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.getConnectionProperties().getSsl().setBundle("missing");
        KafkaClientSettings settings = new KafkaClientSettings("app", "svc-1", new DefaultSslBundleRegistry(),
                List.of(), List.of(), KafkaDomainMetrics.NONE);

        assertThatThrownBy(() -> KafkaSetupUtils.createMessageListener(props, NoOpSchemaValidator.INSTANCE, settings))
                .hasMessageContaining("missing");
    }

    /**
     * KC-21 / KC-08: the reactor-kafka options without a property are adjusted with customizers, applied after the
     * values of the library.
     */
    @Test
    void shouldApplyTheCustomizersOfTheOptions() {
        KafkaReceiverOptionsCustomizer receiver = (domain, options) -> options
                .commitInterval(Duration.ofMillis(250))
                .maxDelayRebalance(Duration.ofSeconds(5));
        KafkaSenderOptionsCustomizer sender = (domain, options) -> options.maxInFlight(64);
        KafkaClientSettings settings = new KafkaClientSettings("app", "svc-1", null, List.of(receiver),
                List.of(sender), KafkaDomainMetrics.NONE);

        ReceiverOptions<String, byte[]> receiverOptions = KafkaSetupUtils.createReceiverOptions(new KafkaProperties(),
                settings);
        SenderOptions<String, byte[]> senderOptions = KafkaSetupUtils.createSenderOptions(new KafkaProperties(),
                Duration.ofSeconds(3), settings);

        assertThat(receiverOptions.commitInterval()).isEqualTo(Duration.ofMillis(250));
        assertThat(receiverOptions.maxDelayRebalance()).isEqualTo(Duration.ofSeconds(5));
        assertThat(receiverOptions.closeTimeout()).as("value of the library kept").isEqualTo(Duration.ofSeconds(10));
        assertThat(senderOptions.maxInFlight()).isEqualTo(64);
    }

    /**
     * What the delivery depends on is set again after the customizers.
     */
    @Test
    void shouldNotLetTheCustomizersDisableWhatProtectsTheDelivery() {
        KafkaReceiverOptionsCustomizer receiver = (domain, options) -> options
                .consumerProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        KafkaSenderOptionsCustomizer sender = (domain, options) -> options.stopOnError(true);
        KafkaClientSettings settings = new KafkaClientSettings("app", "svc-1", null, List.of(receiver),
                List.of(sender), KafkaDomainMetrics.NONE);

        ReceiverOptions<String, byte[]> receiverOptions = KafkaSetupUtils.createReceiverOptions(new KafkaProperties(),
                settings);
        SenderOptions<String, byte[]> senderOptions = KafkaSetupUtils.createSenderOptions(new KafkaProperties(),
                Duration.ofSeconds(3), settings);

        assertThat(receiverOptions.consumerProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo("false");
        assertThat(senderOptions.stopOnError()).isFalse();
    }

    /**
     * The properties are checked after the customizers, so a value a customizer sets is checked too.
     */
    @Test
    void shouldNotCreateTheListenerWithAConsumerPropertyACustomizerSetsAndTheClientRejects() {
        KafkaReceiverOptionsCustomizer receiver = (domain, options) -> options
                .consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "lastest");
        KafkaClientSettings settings = new KafkaClientSettings("app", "svc-1", null, List.of(receiver),
                List.of(), KafkaDomainMetrics.NONE);
        KafkaProperties props = new KafkaProperties();

        assertThatThrownBy(() -> KafkaSetupUtils.createReceiverOptions(props, settings))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
    }

    @Test
    void shouldNotCreateTheSenderWithAProducerPropertyACustomizerSetsAndTheClientRejects() {
        KafkaSenderOptionsCustomizer sender = (domain, options) -> options
                .producerProperty(ProducerConfig.ACKS_CONFIG, "everything");
        KafkaClientSettings settings = new KafkaClientSettings("app", "svc-1", null, List.of(),
                List.of(sender), KafkaDomainMetrics.NONE);
        KafkaProperties props = new KafkaProperties();
        Duration sendTimeout = Duration.ofSeconds(3);

        assertThatThrownBy(() -> KafkaSetupUtils.createSenderOptions(props, sendTimeout, settings))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(ProducerConfig.ACKS_CONFIG);
    }

    /**
     * CR-09: checking the properties does not write the values of the consumer in the log, and those of the producer
     * at most once (the Kafka producer has no public way to check them without logging them), instead of twice each
     * per domain. The values are written by the logger of {@link AbstractConfig}; those of the clients are captured
     * too.
     */
    @Test
    void shouldNotLogTheConfigurationOfTheClientsEachTimeItIsChecked() {
        List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(AbstractConfig.class),
                (Logger) LoggerFactory.getLogger(ConsumerConfig.class),
                (Logger) LoggerFactory.getLogger(ProducerConfig.class));
        List<Level> levels = loggers.stream().map(Logger::getLevel).toList();
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        loggers.forEach(logger -> {
            logger.addAppender(logged);
            logger.setLevel(Level.INFO);
        });
        try {
            KafkaSetupUtils.createReceiverOptions(new KafkaProperties());
            KafkaSetupUtils.createSenderOptions(new KafkaProperties());
        } finally {
            for (int i = 0; i < loggers.size(); i++) {
                loggers.get(i).detachAppender(logged);
                loggers.get(i).setLevel(levels.get(i));
            }
            logged.stop();
        }

        // Each dump lists every property of the client: auto.offset.reset only exists in the consumer, acks only in
        // the producer
        assertThat(dumps(logged, ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).as("consumer dumps").isZero();
        assertThat(dumps(logged, ProducerConfig.ACKS_CONFIG)).as("producer dumps").isLessThanOrEqualTo(1);
    }

    private static long dumps(ListAppender<ILoggingEvent> logged, String property) {
        return logged.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(" values: ") && message.contains("\t" + property + " = "))
                .count();
    }

    /**
     * KC-21: the properties that are bound and have no effect are reported by name. {@code admin.fail-fast} only when
     * it is {@code true}: its default ({@code false}) is what the library does anyway.
     */
    @Test
    void shouldTellThePropertiesThatHaveNoEffect() {
        KafkaProperties props = new KafkaProperties();
        assertThat(KafkaSetupUtils.ignoredProperties(props)).isEmpty();

        props.getAdmin().setFailFast(false);
        assertThat(KafkaSetupUtils.ignoredProperties(props)).isEmpty();

        props.getConsumer().setEnableAutoCommit(true);
        props.getConsumer().setAutoCommitInterval(Duration.ofSeconds(1));
        props.getAdmin().setFailFast(true);
        props.getListener().setConcurrency(3);
        props.getTemplate().setDefaultTopic("orders");
        props.getRetry().getTopic().setEnabled(true);
        props.getStreams().setApplicationId("streams-app");
        props.getJaas().setEnabled(true);

        assertThat(KafkaSetupUtils.ignoredProperties(props)).containsExactly("consumer.enable-auto-commit",
                "consumer.auto-commit-interval", "admin.fail-fast", "listener.*", "template.*", "retry.*",
                "streams.*", "jaas.*");
    }

    /**
     * KC-24: each client gets a {@code client.id} of its own, on its own properties: the properties of the domain,
     * shared by every client, are not changed.
     */
    @Test
    void shouldGiveEachClientItsOwnClientIdWithoutChangingTheSharedProperties() {
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.setAppName("orders");
        KafkaProperties connection = props.getConnectionProperties();
        String base = KafkaClientSettings.clientIdBase(props);
        KafkaClientSettings settings = new KafkaClientSettings("app", base, null, List.of(), List.of(),
                KafkaDomainMetrics.NONE);

        SenderOptions<String, byte[]> sender = KafkaSetupUtils.createSenderOptions(connection, Duration.ofSeconds(3),
                settings);
        ReceiverOptions<String, byte[]> receiver = KafkaSetupUtils.createReceiverOptions(connection, settings);

        assertThat(base).matches("orders-[0-9a-f]{6}");
        assertThat(sender.producerProperties()).containsEntry(ProducerConfig.CLIENT_ID_CONFIG, base + "-producer");
        assertThat(receiver.consumerProperties()).containsEntry(ConsumerConfig.CLIENT_ID_CONFIG, base + "-consumer");
        assertThat(settings.adminClientId()).isEqualTo(base + "-admin");
        assertThat(connection.getClientId()).isNull();
        assertThat(connection.getProducer().getValueSerializer()).isEqualTo(StringSerializer.class);
        assertThat(KafkaClientSettings.clientIdBase(props)).as("another instance").isNotEqualTo(base);
    }

    @Test
    void shouldBaseTheClientIdOnTheConfiguredOne() {
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.setAppName("orders");
        props.getConnectionProperties().setClientId("billing");

        assertThat(KafkaClientSettings.clientIdBase(props)).startsWith("billing-");
    }

    private static Set<String> producerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("kafka-producer-network-thread"))
                .collect(Collectors.toSet());
    }
}
