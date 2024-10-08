/*
Copied from https://github.com/spring-projects/spring-boot/blob/main/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/kafka/KafkaProperties.java
 */

package org.reactivecommons.async.kafka.config.spring;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.SslBundleSslEngineFactory;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.context.properties.source.MutuallyExclusiveConfigurationPropertiesException;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.io.Resource;
import org.springframework.util.CollectionUtils;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration properties for Spring for Apache Kafka.
 * <p>
 * Users should refer to Kafka documentation for complete descriptions of these
 * properties.
 *
 * @author Gary Russell
 * @author Stephane Nicoll
 * @author Artem Bilan
 * @author Nakul Mishra
 * @author Tomaz Fernandes
 * @author Andy Wilkinson
 * @author Scott Frederick
 * @since 1.5.0
 */
public class KafkaPropertiesBase {

    /**
     * Comma-delimited list of host:port pairs to use for establishing the initial
     * connections to the Kafka cluster. Applies to all components unless overridden.
     */
    private List<String> bootstrapServers = new ArrayList<>(Collections.singletonList("localhost:9092"));

    /**
     * ID to pass to the server when making requests. Used for server-side logging.
     */
    private String clientId;

    /**
     * Additional properties, common to producers and consumers, used to configure the
     * client.
     */
    private final Map<String, String> properties = new HashMap<>();

    private final Consumer consumer = new Consumer();

    private final Producer producer = new Producer();

    private final Admin admin = new Admin();

    private final Streams streams = new Streams();

    private final Listener listener = new Listener();

    private final Ssl ssl = new Ssl();

    private final Jaas jaas = new Jaas();

    private final Template template = new Template();

    private final Security security = new Security();

    private final Retry retry = new Retry();

    public List<String> getBootstrapServers() {
        return this.bootstrapServers;
    }

    public void setBootstrapServers(List<String> bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getClientId() {
        return this.clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public Map<String, String> getProperties() {
        return this.properties;
    }

    public Consumer getConsumer() {
        return this.consumer;
    }

    public Producer getProducer() {
        return this.producer;
    }

    public Listener getListener() {
        return this.listener;
    }

    public Admin getAdmin() {
        return this.admin;
    }

    public Streams getStreams() {
        return this.streams;
    }

    public Ssl getSsl() {
        return this.ssl;
    }

    public Jaas getJaas() {
        return this.jaas;
    }

    public Template getTemplate() {
        return this.template;
    }

    public Security getSecurity() {
        return this.security;
    }

    public Retry getRetry() {
        return this.retry;
    }

    private Map<String, Object> buildCommonProperties(SslBundles sslBundles) {
        Map<String, Object> properties = new HashMap<>();
        if (this.bootstrapServers != null) {
            properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, this.bootstrapServers);
        }
        if (this.clientId != null) {
            properties.put(CommonClientConfigs.CLIENT_ID_CONFIG, this.clientId);
        }
        properties.putAll(this.ssl.buildProperties(sslBundles));
        properties.putAll(this.security.buildProperties());
        if (!CollectionUtils.isEmpty(this.properties)) {
            properties.putAll(this.properties);
        }
        return properties;
    }

    /**
     * Create an initial map of consumer properties from the state of this instance.
     * <p>
     * This allows you to add additional properties, if necessary, and override the
     * default {@code kafkaConsumerFactory} bean.
     *
     * @param sslBundles bundles providing SSL trust material
     * @return the consumer properties initialized with the customizations defined on this
     * instance
     */
    public Map<String, Object> buildConsumerProperties(SslBundles sslBundles) {
        Map<String, Object> properties = buildCommonProperties(sslBundles);
        properties.putAll(this.consumer.buildProperties(sslBundles));
        return properties;
    }

    /**
     * Create an initial map of producer properties from the state of this instance.
     * <p>
     * This allows you to add additional properties, if necessary, and override the
     * default {@code kafkaProducerFactory} bean.
     *
     * @param sslBundles bundles providing SSL trust material
     * @return the producer properties initialized with the customizations defined on this
     * instance
     */
    public Map<String, Object> buildProducerProperties(SslBundles sslBundles) {
        Map<String, Object> properties = buildCommonProperties(sslBundles);
        properties.putAll(this.producer.buildProperties(sslBundles));
        return properties;
    }

    /**
     * Create an initial map of admin properties from the state of this instance.
     * <p>
     * This allows you to add additional properties, if necessary, and override the
     * default {@code kafkaAdmin} bean.
     *
     * @param sslBundles bundles providing SSL trust material
     * @return the admin properties initialized with the customizations defined on this
     * instance
     */
    public Map<String, Object> buildAdminProperties(SslBundles sslBundles) {
        Map<String, Object> properties = buildCommonProperties(sslBundles);
        properties.putAll(this.admin.buildProperties(sslBundles));
        return properties;
    }

    /**
     * Create an initial map of streams properties from the state of this instance.
     * <p>
     * This allows you to add additional properties, if necessary.
     *
     * @param sslBundles bundles providing SSL trust material
     * @return the streams properties initialized with the customizations defined on this
     * instance
     */
    public Map<String, Object> buildStreamsProperties(SslBundles sslBundles) {
        Map<String, Object> properties = buildCommonProperties(sslBundles);
        properties.putAll(this.streams.buildProperties(sslBundles));
        return properties;
    }

    public static class Consumer {

        private final Ssl ssl = new Ssl();

        private final Security security = new Security();

        /**
         * Frequency with which the consumer offsets are auto-committed to Kafka if
         * 'enable.auto.commit' is set to true.
         */
        private Duration autoCommitInterval;

        /**
         * What to do when there is no initial offset in Kafka or if the current offset no
         * longer exists on the server.
         */
        private String autoOffsetReset;

        /**
         * Comma-delimited list of host:port pairs to use for establishing the initial
         * connections to the Kafka cluster. Overrides the global property, for consumers.
         */
        private List<String> bootstrapServers;

        /**
         * ID to pass to the server when making requests. Used for server-side logging.
         */
        private String clientId;

        /**
         * Whether the consumer's offset is periodically committed in the background.
         */
        private Boolean enableAutoCommit;

        /**
         * Maximum amount of time the server blocks before answering the fetch request if
         * there isn't sufficient data to immediately satisfy the requirement given by
         * "fetch-min-size".
         */
        private Duration fetchMaxWait;

        /**
         * Minimum amount of data the server should return for a fetch request.
         */
        private DataSize fetchMinSize;

        /**
         * Unique string that identifies the consumer group to which this consumer
         * belongs.
         */
        private String groupId;

        /**
         * Expected time between heartbeats to the consumer coordinator.
         */
        private Duration heartbeatInterval;

        /**
         * Isolation level for reading messages that have been written transactionally.
         */
        private IsolationLevel isolationLevel = IsolationLevel.READ_UNCOMMITTED;

        /**
         * Deserializer class for keys.
         */
        private Class<?> keyDeserializer = StringDeserializer.class;

        /**
         * Deserializer class for values.
         */
        private Class<?> valueDeserializer = StringDeserializer.class;

        /**
         * Maximum number of records returned in a single call to poll().
         */
        private Integer maxPollRecords;

        /**
         * Additional consumer-specific properties used to configure the client.
         */
        private final Map<String, String> properties = new HashMap<>();

        public Ssl getSsl() {
            return this.ssl;
        }

        public Security getSecurity() {
            return this.security;
        }

        public Duration getAutoCommitInterval() {
            return this.autoCommitInterval;
        }

        public void setAutoCommitInterval(Duration autoCommitInterval) {
            this.autoCommitInterval = autoCommitInterval;
        }

        public String getAutoOffsetReset() {
            return this.autoOffsetReset;
        }

        public void setAutoOffsetReset(String autoOffsetReset) {
            this.autoOffsetReset = autoOffsetReset;
        }

        public List<String> getBootstrapServers() {
            return this.bootstrapServers;
        }

        public void setBootstrapServers(List<String> bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getClientId() {
            return this.clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public Boolean getEnableAutoCommit() {
            return this.enableAutoCommit;
        }

        public void setEnableAutoCommit(Boolean enableAutoCommit) {
            this.enableAutoCommit = enableAutoCommit;
        }

        public Duration getFetchMaxWait() {
            return this.fetchMaxWait;
        }

        public void setFetchMaxWait(Duration fetchMaxWait) {
            this.fetchMaxWait = fetchMaxWait;
        }

        public DataSize getFetchMinSize() {
            return this.fetchMinSize;
        }

        public void setFetchMinSize(DataSize fetchMinSize) {
            this.fetchMinSize = fetchMinSize;
        }

        public String getGroupId() {
            return this.groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public Duration getHeartbeatInterval() {
            return this.heartbeatInterval;
        }

        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }

        public IsolationLevel getIsolationLevel() {
            return this.isolationLevel;
        }

        public void setIsolationLevel(IsolationLevel isolationLevel) {
            this.isolationLevel = isolationLevel;
        }

        public Class<?> getKeyDeserializer() {
            return this.keyDeserializer;
        }

        public void setKeyDeserializer(Class<?> keyDeserializer) {
            this.keyDeserializer = keyDeserializer;
        }

        public Class<?> getValueDeserializer() {
            return this.valueDeserializer;
        }

        public void setValueDeserializer(Class<?> valueDeserializer) {
            this.valueDeserializer = valueDeserializer;
        }

        public Integer getMaxPollRecords() {
            return this.maxPollRecords;
        }

        public void setMaxPollRecords(Integer maxPollRecords) {
            this.maxPollRecords = maxPollRecords;
        }

        public Map<String, String> getProperties() {
            return this.properties;
        }

        public Map<String, Object> buildProperties(SslBundles sslBundles) {
            Properties properties = new Properties();
            PropertyMapper map = PropertyMapper.get().alwaysApplyingWhenNonNull();
            map.from(this::getAutoCommitInterval)
                    .asInt(Duration::toMillis)
                    .to(properties.in(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG));
            map.from(this::getAutoOffsetReset).to(properties.in(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
            map.from(this::getBootstrapServers).to(properties.in(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
            map.from(this::getClientId).to(properties.in(ConsumerConfig.CLIENT_ID_CONFIG));
            map.from(this::getEnableAutoCommit).to(properties.in(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
            map.from(this::getFetchMaxWait)
                    .asInt(Duration::toMillis)
                    .to(properties.in(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG));
            map.from(this::getFetchMinSize)
                    .asInt(DataSize::toBytes)
                    .to(properties.in(ConsumerConfig.FETCH_MIN_BYTES_CONFIG));
            map.from(this::getGroupId).to(properties.in(ConsumerConfig.GROUP_ID_CONFIG));
            map.from(this::getHeartbeatInterval)
                    .asInt(Duration::toMillis)
                    .to(properties.in(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG));
            map.from(() -> getIsolationLevel().name().toLowerCase(Locale.ROOT))
                    .to(properties.in(ConsumerConfig.ISOLATION_LEVEL_CONFIG));
            map.from(this::getKeyDeserializer).to(properties.in(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
            map.from(this::getValueDeserializer).to(properties.in(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
            map.from(this::getMaxPollRecords).to(properties.in(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
            return properties.with(this.ssl, this.security, this.properties, sslBundles);
        }

    }

    public static class Producer {

        private final Ssl ssl = new Ssl();

        private final Security security = new Security();

        /**
         * Number of acknowledgments the producer requires the leader to have received
         * before considering a request complete.
         */
        private String acks;

        /**
         * Default batch size. A small batch size will make batching less common and may
         * reduce throughput (a batch size of zero disables batching entirely).
         */
        private DataSize batchSize;

        /**
         * Comma-delimited list of host:port pairs to use for establishing the initial
         * connections to the Kafka cluster. Overrides the global property, for producers.
         */
        private List<String> bootstrapServers;

        /**
         * Total memory size the producer can use to buffer records waiting to be sent to
         * the server.
         */
        private DataSize bufferMemory;

        /**
         * ID to pass to the server when making requests. Used for server-side logging.
         */
        private String clientId;

        /**
         * Compression type for all data generated by the producer.
         */
        private String compressionType;

        /**
         * Serializer class for keys.
         */
        private Class<?> keySerializer = StringSerializer.class;

        /**
         * Serializer class for values.
         */
        private Class<?> valueSerializer = StringSerializer.class;

        /**
         * When greater than zero, enables retrying of failed sends.
         */
        private Integer retries;

        /**
         * When non empty, enables transaction support for producer.
         */
        private String transactionIdPrefix;

        /**
         * Additional producer-specific properties used to configure the client.
         */
        private final Map<String, String> properties = new HashMap<>();

        public Ssl getSsl() {
            return this.ssl;
        }

        public Security getSecurity() {
            return this.security;
        }

        public String getAcks() {
            return this.acks;
        }

        public void setAcks(String acks) {
            this.acks = acks;
        }

        public DataSize getBatchSize() {
            return this.batchSize;
        }

        public void setBatchSize(DataSize batchSize) {
            this.batchSize = batchSize;
        }

        public List<String> getBootstrapServers() {
            return this.bootstrapServers;
        }

        public void setBootstrapServers(List<String> bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public DataSize getBufferMemory() {
            return this.bufferMemory;
        }

        public void setBufferMemory(DataSize bufferMemory) {
            this.bufferMemory = bufferMemory;
        }

        public String getClientId() {
            return this.clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getCompressionType() {
            return this.compressionType;
        }

        public void setCompressionType(String compressionType) {
            this.compressionType = compressionType;
        }

        public Class<?> getKeySerializer() {
            return this.keySerializer;
        }

        public void setKeySerializer(Class<?> keySerializer) {
            this.keySerializer = keySerializer;
        }

        public Class<?> getValueSerializer() {
            return this.valueSerializer;
        }

        public void setValueSerializer(Class<?> valueSerializer) {
            this.valueSerializer = valueSerializer;
        }

        public Integer getRetries() {
            return this.retries;
        }

        public void setRetries(Integer retries) {
            this.retries = retries;
        }

        public String getTransactionIdPrefix() {
            return this.transactionIdPrefix;
        }

        public void setTransactionIdPrefix(String transactionIdPrefix) {
            this.transactionIdPrefix = transactionIdPrefix;
        }

        public Map<String, String> getProperties() {
            return this.properties;
        }

        public Map<String, Object> buildProperties(SslBundles sslBundles) {
            Properties properties = new Properties();
            PropertyMapper map = PropertyMapper.get().alwaysApplyingWhenNonNull();
            map.from(this::getAcks).to(properties.in(ProducerConfig.ACKS_CONFIG));
            map.from(this::getBatchSize).asInt(DataSize::toBytes).to(properties.in(ProducerConfig.BATCH_SIZE_CONFIG));
            map.from(this::getBootstrapServers).to(properties.in(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
            map.from(this::getBufferMemory)
                    .as(DataSize::toBytes)
                    .to(properties.in(ProducerConfig.BUFFER_MEMORY_CONFIG));
            map.from(this::getClientId).to(properties.in(ProducerConfig.CLIENT_ID_CONFIG));
            map.from(this::getCompressionType).to(properties.in(ProducerConfig.COMPRESSION_TYPE_CONFIG));
            map.from(this::getKeySerializer).to(properties.in(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
            map.from(this::getRetries).to(properties.in(ProducerConfig.RETRIES_CONFIG));
            map.from(this::getValueSerializer).to(properties.in(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
            return properties.with(this.ssl, this.security, this.properties, sslBundles);
        }

    }

    public static class Admin {

        private final Ssl ssl = new Ssl();

        private final Security security = new Security();

        /**
         * ID to pass to the server when making requests. Used for server-side logging.
         */
        private String clientId;

        /**
         * Additional admin-specific properties used to configure the client.
         */
        private final Map<String, String> properties = new HashMap<>();

        /**
         * Close timeout.
         */
        private Duration closeTimeout;

        /**
         * Operation timeout.
         */
        private Duration operationTimeout;

        /**
         * Whether to fail fast if the broker is not available on startup.
         */
        private boolean failFast;

        /**
         * Whether to enable modification of existing topic configuration.
         */
        private boolean modifyTopicConfigs;

        /**
         * Whether to automatically create topics during context initialization. When set
         * to false, disables automatic topic creation during context initialization.
         */
        private boolean autoCreate = true;

        public Ssl getSsl() {
            return this.ssl;
        }

        public Security getSecurity() {
            return this.security;
        }

        public String getClientId() {
            return this.clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public Duration getCloseTimeout() {
            return this.closeTimeout;
        }

        public void setCloseTimeout(Duration closeTimeout) {
            this.closeTimeout = closeTimeout;
        }

        public Duration getOperationTimeout() {
            return this.operationTimeout;
        }

        public void setOperationTimeout(Duration operationTimeout) {
            this.operationTimeout = operationTimeout;
        }

        public boolean isFailFast() {
            return this.failFast;
        }

        public void setFailFast(boolean failFast) {
            this.failFast = failFast;
        }

        public boolean isModifyTopicConfigs() {
            return this.modifyTopicConfigs;
        }

        public void setModifyTopicConfigs(boolean modifyTopicConfigs) {
            this.modifyTopicConfigs = modifyTopicConfigs;
        }

        public boolean isAutoCreate() {
            return this.autoCreate;
        }

        public void setAutoCreate(boolean autoCreate) {
            this.autoCreate = autoCreate;
        }

        public Map<String, String> getProperties() {
            return this.properties;
        }

        public Map<String, Object> buildProperties(SslBundles sslBundles) {
            Properties properties = new Properties();
            PropertyMapper map = PropertyMapper.get().alwaysApplyingWhenNonNull();
            map.from(this::getClientId).to(properties.in(ProducerConfig.CLIENT_ID_CONFIG));
            return properties.with(this.ssl, this.security, this.properties, sslBundles);
        }

    }

    /**
     * High (and some medium) priority Streams properties and a general properties bucket.
     */
    public static class Streams {

        private final Ssl ssl = new Ssl();

        private final Security security = new Security();

        private final Cleanup cleanup = new Cleanup();

        /**
         * Kafka streams application.id property; default spring.application.name.
         */
        private String applicationId;

        /**
         * Whether to auto-start the streams factory bean.
         */
        private boolean autoStartup = true;

        /**
         * Comma-delimited list of host:port pairs to use for establishing the initial
         * connections to the Kafka cluster. Overrides the global property, for streams.
         */
        private List<String> bootstrapServers;

        /**
         * Maximum size of the in-memory state store cache across all threads.
         */
        private DataSize stateStoreCacheMaxSize;

        /**
         * ID to pass to the server when making requests. Used for server-side logging.
         */
        private String clientId;

        /**
         * The replication factor for change log topics and repartition topics created by
         * the stream processing application.
         */
        private Integer replicationFactor;

        /**
         * Directory location for the state store.
         */
        private String stateDir;

        /**
         * Additional Kafka properties used to configure the streams.
         */
        private final Map<String, String> properties = new HashMap<>();

        public Ssl getSsl() {
            return this.ssl;
        }

        public Security getSecurity() {
            return this.security;
        }

        public Cleanup getCleanup() {
            return this.cleanup;
        }

        public String getApplicationId() {
            return this.applicationId;
        }

        public void setApplicationId(String applicationId) {
            this.applicationId = applicationId;
        }

        public boolean isAutoStartup() {
            return this.autoStartup;
        }

        public void setAutoStartup(boolean autoStartup) {
            this.autoStartup = autoStartup;
        }

        public List<String> getBootstrapServers() {
            return this.bootstrapServers;
        }

        public void setBootstrapServers(List<String> bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public DataSize getStateStoreCacheMaxSize() {
            return this.stateStoreCacheMaxSize;
        }

        public void setStateStoreCacheMaxSize(DataSize stateStoreCacheMaxSize) {
            this.stateStoreCacheMaxSize = stateStoreCacheMaxSize;
        }

        public String getClientId() {
            return this.clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public Integer getReplicationFactor() {
            return this.replicationFactor;
        }

        public void setReplicationFactor(Integer replicationFactor) {
            this.replicationFactor = replicationFactor;
        }

        public String getStateDir() {
            return this.stateDir;
        }

        public void setStateDir(String stateDir) {
            this.stateDir = stateDir;
        }

        public Map<String, String> getProperties() {
            return this.properties;
        }

        public Map<String, Object> buildProperties(SslBundles sslBundles) {
            Properties properties = new Properties();
            PropertyMapper map = PropertyMapper.get().alwaysApplyingWhenNonNull();
            map.from(this::getApplicationId).to(properties.in("application.id"));
            map.from(this::getBootstrapServers).to(properties.in(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG));
            map.from(this::getStateStoreCacheMaxSize)
                    .asInt(DataSize::toBytes)
                    .to(properties.in("statestore.cache.max.bytes"));
            map.from(this::getClientId).to(properties.in(CommonClientConfigs.CLIENT_ID_CONFIG));
            map.from(this::getReplicationFactor).to(properties.in("replication.factor"));
            map.from(this::getStateDir).to(properties.in("state.dir"));
            return properties.with(this.ssl, this.security, this.properties, sslBundles);
        }

    }

    public static class Template {

        /**
         * Default topic to which messages are sent.
         */
        private String defaultTopic;

        /**
         * Transaction id prefix, override the transaction id prefix in the producer
         * factory.
         */
        private String transactionIdPrefix;

        /**
         * Whether to enable observation.
         */
        private boolean observationEnabled;

        public String getDefaultTopic() {
            return this.defaultTopic;
        }

        public void setDefaultTopic(String defaultTopic) {
            this.defaultTopic = defaultTopic;
        }

        public String getTransactionIdPrefix() {
            return this.transactionIdPrefix;
        }

        public void setTransactionIdPrefix(String transactionIdPrefix) {
            this.transactionIdPrefix = transactionIdPrefix;
        }

        public boolean isObservationEnabled() {
            return this.observationEnabled;
        }

        public void setObservationEnabled(boolean observationEnabled) {
            this.observationEnabled = observationEnabled;
        }

    }

    public static class Listener {

        public enum Type {

            /**
             * Invokes the endpoint with one ConsumerRecord at a time.
             */
            SINGLE,

            /**
             * Invokes the endpoint with a batch of ConsumerRecords.
             */
            BATCH

        }

        /**
         * Listener type.
         */
        private Type type = Type.SINGLE;

        /**
         * Support for asynchronous record acknowledgements. Only applies when
         * spring.kafka.listener.ack-mode is manual or manual-immediate.
         */
        private Boolean asyncAcks;

        /**
         * Prefix for the listener's consumer client.id property.
         */
        private String clientId;

        /**
         * Number of threads to run in the listener containers.
         */
        private Integer concurrency;

        /**
         * Timeout to use when polling the consumer.
         */
        private Duration pollTimeout;

        /**
         * Multiplier applied to "pollTimeout" to determine if a consumer is
         * non-responsive.
         */
        private Float noPollThreshold;

        /**
         * Number of records between offset commits when ackMode is "COUNT" or
         * "COUNT_TIME".
         */
        private Integer ackCount;

        /**
         * Time between offset commits when ackMode is "TIME" or "COUNT_TIME".
         */
        private Duration ackTime;

        /**
         * Sleep interval between Consumer.poll(Duration) calls.
         */
        private Duration idleBetweenPolls = Duration.ZERO;

        /**
         * Time between publishing idle consumer events (no data received).
         */
        private Duration idleEventInterval;

        /**
         * Time between publishing idle partition consumer events (no data received for
         * partition).
         */
        private Duration idlePartitionEventInterval;

        /**
         * Time between checks for non-responsive consumers. If a duration suffix is not
         * specified, seconds will be used.
         */
        @DurationUnit(ChronoUnit.SECONDS)
        private Duration monitorInterval;

        /**
         * Whether to log the container configuration during initialization (INFO level).
         */
        private Boolean logContainerConfig;

        /**
         * Whether the container should fail to start if at least one of the configured
         * topics are not present on the broker.
         */
        private boolean missingTopicsFatal = false;

        /**
         * Whether the container stops after the current record is processed or after all
         * the records from the previous poll are processed.
         */
        private boolean immediateStop = false;

        /**
         * Whether to auto start the container.
         */
        private boolean autoStartup = true;

        /**
         * Whether to instruct the container to change the consumer thread name during
         * initialization.
         */
        private Boolean changeConsumerThreadName;

        /**
         * Whether to enable observation.
         */
        private boolean observationEnabled;

        public Type getType() {
            return this.type;
        }

        public void setType(Type type) {
            this.type = type;
        }

        public Boolean getAsyncAcks() {
            return this.asyncAcks;
        }

        public void setAsyncAcks(Boolean asyncAcks) {
            this.asyncAcks = asyncAcks;
        }

        public String getClientId() {
            return this.clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public Integer getConcurrency() {
            return this.concurrency;
        }

        public void setConcurrency(Integer concurrency) {
            this.concurrency = concurrency;
        }

        public Duration getPollTimeout() {
            return this.pollTimeout;
        }

        public void setPollTimeout(Duration pollTimeout) {
            this.pollTimeout = pollTimeout;
        }

        public Float getNoPollThreshold() {
            return this.noPollThreshold;
        }

        public void setNoPollThreshold(Float noPollThreshold) {
            this.noPollThreshold = noPollThreshold;
        }

        public Integer getAckCount() {
            return this.ackCount;
        }

        public void setAckCount(Integer ackCount) {
            this.ackCount = ackCount;
        }

        public Duration getAckTime() {
            return this.ackTime;
        }

        public void setAckTime(Duration ackTime) {
            this.ackTime = ackTime;
        }

        public Duration getIdleBetweenPolls() {
            return this.idleBetweenPolls;
        }

        public void setIdleBetweenPolls(Duration idleBetweenPolls) {
            this.idleBetweenPolls = idleBetweenPolls;
        }

        public Duration getIdleEventInterval() {
            return this.idleEventInterval;
        }

        public void setIdleEventInterval(Duration idleEventInterval) {
            this.idleEventInterval = idleEventInterval;
        }

        public Duration getIdlePartitionEventInterval() {
            return this.idlePartitionEventInterval;
        }

        public void setIdlePartitionEventInterval(Duration idlePartitionEventInterval) {
            this.idlePartitionEventInterval = idlePartitionEventInterval;
        }

        public Duration getMonitorInterval() {
            return this.monitorInterval;
        }

        public void setMonitorInterval(Duration monitorInterval) {
            this.monitorInterval = monitorInterval;
        }

        public Boolean getLogContainerConfig() {
            return this.logContainerConfig;
        }

        public void setLogContainerConfig(Boolean logContainerConfig) {
            this.logContainerConfig = logContainerConfig;
        }

        public boolean isMissingTopicsFatal() {
            return this.missingTopicsFatal;
        }

        public void setMissingTopicsFatal(boolean missingTopicsFatal) {
            this.missingTopicsFatal = missingTopicsFatal;
        }

        public boolean isImmediateStop() {
            return this.immediateStop;
        }

        public void setImmediateStop(boolean immediateStop) {
            this.immediateStop = immediateStop;
        }

        public boolean isAutoStartup() {
            return this.autoStartup;
        }

        public void setAutoStartup(boolean autoStartup) {
            this.autoStartup = autoStartup;
        }

        public Boolean getChangeConsumerThreadName() {
            return this.changeConsumerThreadName;
        }

        public void setChangeConsumerThreadName(Boolean changeConsumerThreadName) {
            this.changeConsumerThreadName = changeConsumerThreadName;
        }

        public boolean isObservationEnabled() {
            return this.observationEnabled;
        }

        public void setObservationEnabled(boolean observationEnabled) {
            this.observationEnabled = observationEnabled;
        }

    }

    public static class Ssl {

        /**
         * Name of the SSL bundle to use.
         */
        private String bundle;

        /**
         * Password of the private key in either key store key or key store file.
         */
        private String keyPassword;

        /**
         * Certificate chain in PEM format with a list of X.509 certificates.
         */
        private String keyStoreCertificateChain;

        /**
         * Private key in PEM format with PKCS#8 keys.
         */
        private String keyStoreKey;

        /**
         * Location of the key store file.
         */
        private Resource keyStoreLocation;

        /**
         * Store password for the key store file.
         */
        private String keyStorePassword;

        /**
         * Type of the key store.
         */
        private String keyStoreType;

        /**
         * Trusted certificates in PEM format with X.509 certificates.
         */
        private String trustStoreCertificates;

        /**
         * Location of the trust store file.
         */
        private Resource trustStoreLocation;

        /**
         * Store password for the trust store file.
         */
        private String trustStorePassword;

        /**
         * Type of the trust store.
         */
        private String trustStoreType;

        /**
         * SSL protocol to use.
         */
        private String protocol;

        public String getBundle() {
            return this.bundle;
        }

        public void setBundle(String bundle) {
            this.bundle = bundle;
        }

        public String getKeyPassword() {
            return this.keyPassword;
        }

        public void setKeyPassword(String keyPassword) {
            this.keyPassword = keyPassword;
        }

        public String getKeyStoreCertificateChain() {
            return this.keyStoreCertificateChain;
        }

        public void setKeyStoreCertificateChain(String keyStoreCertificateChain) {
            this.keyStoreCertificateChain = keyStoreCertificateChain;
        }

        public String getKeyStoreKey() {
            return this.keyStoreKey;
        }

        public void setKeyStoreKey(String keyStoreKey) {
            this.keyStoreKey = keyStoreKey;
        }

        public Resource getKeyStoreLocation() {
            return this.keyStoreLocation;
        }

        public void setKeyStoreLocation(Resource keyStoreLocation) {
            this.keyStoreLocation = keyStoreLocation;
        }

        public String getKeyStorePassword() {
            return this.keyStorePassword;
        }

        public void setKeyStorePassword(String keyStorePassword) {
            this.keyStorePassword = keyStorePassword;
        }

        public String getKeyStoreType() {
            return this.keyStoreType;
        }

        public void setKeyStoreType(String keyStoreType) {
            this.keyStoreType = keyStoreType;
        }

        public String getTrustStoreCertificates() {
            return this.trustStoreCertificates;
        }

        public void setTrustStoreCertificates(String trustStoreCertificates) {
            this.trustStoreCertificates = trustStoreCertificates;
        }

        public Resource getTrustStoreLocation() {
            return this.trustStoreLocation;
        }

        public void setTrustStoreLocation(Resource trustStoreLocation) {
            this.trustStoreLocation = trustStoreLocation;
        }

        public String getTrustStorePassword() {
            return this.trustStorePassword;
        }

        public void setTrustStorePassword(String trustStorePassword) {
            this.trustStorePassword = trustStorePassword;
        }

        public String getTrustStoreType() {
            return this.trustStoreType;
        }

        public void setTrustStoreType(String trustStoreType) {
            this.trustStoreType = trustStoreType;
        }

        public String getProtocol() {
            return this.protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        @Deprecated(since = "3.2.0", forRemoval = true)
        public Map<String, Object> buildProperties() {
            return buildProperties(null);
        }

        public Map<String, Object> buildProperties(SslBundles sslBundles) {
            validate();
            Properties properties = new Properties();
            if (getBundle() != null) {
                properties.in(SslConfigs.SSL_ENGINE_FACTORY_CLASS_CONFIG)
                        .accept(SslBundleSslEngineFactory.class.getName());
                properties.in(SslBundle.class.getName()).accept(sslBundles.getBundle(getBundle()));
            } else {
                PropertyMapper map = PropertyMapper.get().alwaysApplyingWhenNonNull();
                map.from(this::getKeyPassword).to(properties.in(SslConfigs.SSL_KEY_PASSWORD_CONFIG));
                map.from(this::getKeyStoreCertificateChain)
                        .to(properties.in(SslConfigs.SSL_KEYSTORE_CERTIFICATE_CHAIN_CONFIG));
                map.from(this::getKeyStoreKey).to(properties.in(SslConfigs.SSL_KEYSTORE_KEY_CONFIG));
                map.from(this::getKeyStoreLocation)
                        .as(this::resourceToPath)
                        .to(properties.in(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG));
                map.from(this::getKeyStorePassword).to(properties.in(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG));
                map.from(this::getKeyStoreType).to(properties.in(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG));
                map.from(this::getTrustStoreCertificates)
                        .to(properties.in(SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG));
                map.from(this::getTrustStoreLocation)
                        .as(this::resourceToPath)
                        .to(properties.in(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG));
                map.from(this::getTrustStorePassword).to(properties.in(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG));
                map.from(this::getTrustStoreType).to(properties.in(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG));
                map.from(this::getProtocol).to(properties.in(SslConfigs.SSL_PROTOCOL_CONFIG));
            }
            return properties;
        }

        private void validate() {
            MutuallyExclusiveConfigurationPropertiesException.throwIfMultipleNonNullValuesIn((entries) -> {
                entries.put("spring.kafka.ssl.key-store-key", getKeyStoreKey());
                entries.put("spring.kafka.ssl.key-store-location", getKeyStoreLocation());
            });
            MutuallyExclusiveConfigurationPropertiesException.throwIfMultipleNonNullValuesIn((entries) -> {
                entries.put("spring.kafka.ssl.trust-store-certificates", getTrustStoreCertificates());
                entries.put("spring.kafka.ssl.trust-store-location", getTrustStoreLocation());
            });
            MutuallyExclusiveConfigurationPropertiesException.throwIfMultipleNonNullValuesIn((entries) -> {
                entries.put("spring.kafka.ssl.bundle", getBundle());
                entries.put("spring.kafka.ssl.key-store-key", getKeyStoreKey());
            });
            MutuallyExclusiveConfigurationPropertiesException.throwIfMultipleNonNullValuesIn((entries) -> {
                entries.put("spring.kafka.ssl.bundle", getBundle());
                entries.put("spring.kafka.ssl.key-store-location", getKeyStoreLocation());
            });
            MutuallyExclusiveConfigurationPropertiesException.throwIfMultipleNonNullValuesIn((entries) -> {
                entries.put("spring.kafka.ssl.bundle", getBundle());
                entries.put("spring.kafka.ssl.trust-store-certificates", getTrustStoreCertificates());
            });
            MutuallyExclusiveConfigurationPropertiesException.throwIfMultipleNonNullValuesIn((entries) -> {
                entries.put("spring.kafka.ssl.bundle", getBundle());
                entries.put("spring.kafka.ssl.trust-store-location", getTrustStoreLocation());
            });
        }

        private String resourceToPath(Resource resource) {
            try {
                return resource.getFile().getAbsolutePath();
            } catch (IOException ex) {
                throw new IllegalStateException("Resource '" + resource + "' must be on a file system", ex);
            }
        }

    }

    public static class Jaas {

        /**
         * Whether to enable JAAS configuration.
         */
        private boolean enabled;

        /**
         * Login module.
         */
        private String loginModule = "com.sun.security.auth.module.Krb5LoginModule";

        /**
         * Additional JAAS options.
         */
        private final Map<String, String> options = new HashMap<>();

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLoginModule() {
            return this.loginModule;
        }

        public void setLoginModule(String loginModule) {
            this.loginModule = loginModule;
        }

        public Map<String, String> getOptions() {
            return this.options;
        }

        public void setOptions(Map<String, String> options) {
            if (options != null) {
                this.options.putAll(options);
            }
        }

    }

    public static class Security {

        /**
         * Security protocol used to communicate with brokers.
         */
        private String protocol;

        public String getProtocol() {
            return this.protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public Map<String, Object> buildProperties() {
            Properties properties = new Properties();
            PropertyMapper map = PropertyMapper.get().alwaysApplyingWhenNonNull();
            map.from(this::getProtocol).to(properties.in(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
            return properties;
        }

    }

    public static class Retry {

        private final Topic topic = new Topic();

        public Topic getTopic() {
            return this.topic;
        }

        /**
         * Properties for non-blocking, topic-based retries.
         */
        public static class Topic {

            /**
             * Whether to enable topic-based non-blocking retries.
             */
            private boolean enabled;

            /**
             * Total number of processing attempts made before sending the message to the
             * DLT.
             */
            private int attempts = 3;

            public boolean isEnabled() {
                return this.enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getAttempts() {
                return this.attempts;
            }

            public void setAttempts(int attempts) {
                this.attempts = attempts;
            }

            @DeprecatedConfigurationProperty(replacement = "spring.kafka.retry.topic.backoff.delay", since = "3.4.0")
            @Deprecated(since = "3.4.0", forRemoval = true)
            public Duration getDelay() {
                return getBackoff().getDelay();
            }

            @Deprecated(since = "3.4.0", forRemoval = true)
            public void setDelay(Duration delay) {
                getBackoff().setDelay(delay);
            }

            @DeprecatedConfigurationProperty(replacement = "spring.kafka.retry.topic.backoff.multiplier",
                    since = "3.4.0")
            @Deprecated(since = "3.4.0", forRemoval = true)
            public double getMultiplier() {
                return getBackoff().getMultiplier();
            }

            @Deprecated(since = "3.4.0", forRemoval = true)
            public void setMultiplier(double multiplier) {
                getBackoff().setMultiplier(multiplier);
            }

            @DeprecatedConfigurationProperty(replacement = "spring.kafka.retry.topic.backoff.maxDelay", since = "3.4.0")
            @Deprecated(since = "3.4.0", forRemoval = true)
            public Duration getMaxDelay() {
                return getBackoff().getMaxDelay();
            }

            @Deprecated(since = "3.4.0", forRemoval = true)
            public void setMaxDelay(Duration maxDelay) {
                getBackoff().setMaxDelay(maxDelay);
            }

            @DeprecatedConfigurationProperty(replacement = "spring.kafka.retry.topic.backoff.random", since = "3.4.0")
            @Deprecated(since = "3.4.0", forRemoval = true)
            public boolean isRandomBackOff() {
                return getBackoff().isRandom();
            }

            @Deprecated(since = "3.4.0", forRemoval = true)
            public void setRandomBackOff(boolean randomBackOff) {
                getBackoff().setRandom(randomBackOff);
            }

            private final Backoff backoff = new Backoff();

            public Backoff getBackoff() {
                return this.backoff;
            }

            public static class Backoff {

                /**
                 * Canonical backoff period. Used as an initial value in the exponential
                 * case, and as a minimum value in the uniform case.
                 */
                private Duration delay = Duration.ofSeconds(1);

                /**
                 * Multiplier to use for generating the next backoff delay.
                 */
                private double multiplier = 0.0;

                /**
                 * Maximum wait between retries. If less than the delay then the default
                 * of 30 seconds is applied.
                 */
                private Duration maxDelay = Duration.ZERO;

                /**
                 * Whether to have the backoff delays.
                 */
                private boolean random = false;

                public Duration getDelay() {
                    return this.delay;
                }

                public void setDelay(Duration delay) {
                    this.delay = delay;
                }

                public double getMultiplier() {
                    return this.multiplier;
                }

                public void setMultiplier(double multiplier) {
                    this.multiplier = multiplier;
                }

                public Duration getMaxDelay() {
                    return this.maxDelay;
                }

                public void setMaxDelay(Duration maxDelay) {
                    this.maxDelay = maxDelay;
                }

                public boolean isRandom() {
                    return this.random;
                }

                public void setRandom(boolean random) {
                    this.random = random;
                }

            }

        }

    }

    public static class Cleanup {

        /**
         * Cleanup the application?s local state directory on startup.
         */
        private boolean onStartup = false;

        /**
         * Cleanup the application?s local state directory on shutdown.
         */
        private boolean onShutdown = false;

        public boolean isOnStartup() {
            return this.onStartup;
        }

        public void setOnStartup(boolean onStartup) {
            this.onStartup = onStartup;
        }

        public boolean isOnShutdown() {
            return this.onShutdown;
        }

        public void setOnShutdown(boolean onShutdown) {
            this.onShutdown = onShutdown;
        }

    }

    public enum IsolationLevel {

        /**
         * Read everything including aborted transactions.
         */
        READ_UNCOMMITTED((byte) 0),

        /**
         * Read records from committed transactions, in addition to records not part of
         * transactions.
         */
        READ_COMMITTED((byte) 1);

        private final byte id;

        IsolationLevel(byte id) {
            this.id = id;
        }

        public byte id() {
            return this.id;
        }

    }

    @SuppressWarnings("serial")
    private static final class Properties extends HashMap<String, Object> {

        <V> java.util.function.Consumer<V> in(String key) {
            return (value) -> put(key, value);
        }

        Properties with(Ssl ssl, Security security, Map<String, String> properties, SslBundles sslBundles) {
            putAll(ssl.buildProperties(sslBundles));
            putAll(security.buildProperties());
            putAll(properties);
            return this;
        }

    }

}