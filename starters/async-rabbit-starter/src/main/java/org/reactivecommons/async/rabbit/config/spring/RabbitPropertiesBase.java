package org.reactivecommons.async.rabbit.config.spring;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class RabbitPropertiesBase {

    /**
     * RabbitMQ host.
     */
    @Setter
    @Getter
    private String host = "localhost";

    /**
     * RabbitMQ port.
     */
    @Setter
    @Getter
    private int port = 5672;

    /**
     * Login user to authenticate to the communications.
     */
    @Setter
    @Getter
    private String username = "guest";

    /**
     * Login to authenticate against the communications.
     */
    @Setter
    @Getter
    private String password = "guest"; //NOSONAR

    /**
     * SSL configuration.
     */
    @Getter
    private final Ssl ssl = new Ssl();

    /**
     * Virtual host to use when connecting to the communications.
     */
    @Getter
    private String virtualHost;

    /**
     * Comma-separated list of addresses to which the client should connect.
     */
    @Getter
    private String addresses;

    /**
     * Requested heartbeat timeout; zero for none. If a duration suffix is not specified,
     * seconds will be used.
     */
    @Getter
    @Setter
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration requestedHeartbeat;

    /**
     * Whether to enable publisher confirms.
     */
    @Setter
    @Getter
    private boolean publisherConfirms;

    /**
     * Whether to enable publisher returns.
     */
    @Setter
    @Getter
    private boolean publisherReturns;

    /**
     * Connection timeout. Set it to zero to wait forever.
     */
    @Getter
    @Setter
    private Duration connectionTimeout;

    /**
     * Cache configuration.
     */
    @Getter
    private final Cache cache = new Cache();

    /**
     * Listener container configuration.
     */
    @Getter
    private final Listener listener = new Listener();

    @Getter
    private final Template template = new Template();

    private List<Address> parsedAddresses;

    /**
     * Returns the host from the first address, or the configured host if no addresses
     * have been set.
     *
     * @return the host
     * @see #setAddresses(String)
     * @see #getHost()
     */
    public String determineHost() {
        if (CollectionUtils.isEmpty(this.parsedAddresses)) {
            return getHost();
        }
        return this.parsedAddresses.get(0).host;
    }

    /**
     * Returns the port from the first address, or the configured port if no addresses
     * have been set.
     *
     * @return the port
     * @see #setAddresses(String)
     * @see #getPort()
     */
    public int determinePort() {
        if (CollectionUtils.isEmpty(this.parsedAddresses)) {
            return getPort();
        }
        Address address = this.parsedAddresses.get(0);
        return address.port;
    }

    /**
     * Returns the comma-separated addresses or a single address ({@code host:port})
     * created from the configured host and port if no addresses have been set.
     *
     * @return the addresses
     */
    public String determineAddresses() {
        if (CollectionUtils.isEmpty(this.parsedAddresses)) {
            return this.host + ":" + this.port;
        }
        List<String> addressStrings = new ArrayList<>();
        for (Address parsedAddress : this.parsedAddresses) {
            addressStrings.add(parsedAddress.host + ":" + parsedAddress.port);
        }
        return StringUtils.collectionToCommaDelimitedString(addressStrings);
    }

    public void setAddresses(String addresses) {
        this.addresses = addresses;
        this.parsedAddresses = parseAddresses(addresses);
    }

    private List<Address> parseAddresses(String addresses) {
        List<Address> parsedAddressesLocal = new ArrayList<>();
        for (String address : StringUtils.commaDelimitedListToStringArray(addresses)) {
            parsedAddressesLocal.add(new Address(address));
        }
        return parsedAddressesLocal;
    }

    /**
     * If addresses have been set and the first address has a username it is returned.
     * Otherwise returns the result of calling {@code getUsername()}.
     *
     * @return the username
     * @see #setAddresses(String)
     * @see #getUsername()
     */
    public String determineUsername() {
        if (CollectionUtils.isEmpty(this.parsedAddresses)) {
            return this.username;
        }
        Address address = this.parsedAddresses.get(0);
        return (address.username != null) ? address.username : this.username;
    }

    /**
     * If addresses have been set and the first address has a password it is returned.
     * Otherwise returns the result of calling {@code getPassword()}.
     *
     * @return the password or {@code null}
     * @see #setAddresses(String)
     * @see #getPassword()
     */
    public String determinePassword() {
        if (CollectionUtils.isEmpty(this.parsedAddresses)) {
            return getPassword();
        }
        Address address = this.parsedAddresses.get(0);
        return (address.password != null) ? address.password : getPassword();
    }

    /**
     * If addresses have been set and the first address has a virtual host it is returned.
     * Otherwise returns the result of calling {@code getVirtualHost()}.
     *
     * @return the virtual host or {@code null}
     * @see #setAddresses(String)
     * @see #getVirtualHost()
     */
    public String determineVirtualHost() {
        if (CollectionUtils.isEmpty(this.parsedAddresses)) {
            return getVirtualHost();
        }
        Address address = this.parsedAddresses.get(0);
        return (address.virtualHost != null) ? address.virtualHost : getVirtualHost();
    }

    public void setVirtualHost(String virtualHost) {
        this.virtualHost = "".equals(virtualHost) ? "/" : virtualHost;
    }

    @Getter
    @Setter
    public static class Ssl {

        /**
         * Whether to enable SSL support.
         */
        private boolean enabled;

        /**
         * Path to the key store that holds the SSL certificate.
         */
        private String keyStore;

        /**
         * Key store type.
         */
        private String keyStoreType = "PKCS12";

        /**
         * Password used to access the key store.
         */
        private String keyStorePassword;

        /**
         * Trust store that holds SSL certificates.
         */
        private String trustStore;

        /**
         * Trust store type.
         */
        private String trustStoreType = "JKS";

        /**
         * Password used to access the trust store.
         */
        private String trustStorePassword;

        /**
         * SSL algorithm to use. By default, configured by the Rabbit client library.
         */
        private String algorithm;

        /**
         * Whether to enable server side certificate validation.
         */
        private boolean validateServerCertificate = true;

        /**
         * Whether to enable hostname verification.
         */
        private boolean verifyHostname = true;

    }

    @Getter
    public static class Cache {

        private final Channel channel = new Channel();

        private final Connection connection = new Connection();

        @Getter
        @Setter
        public static class Channel {

            /**
             * Number of channels to retain in the cache. When "check-timeout" > 0, max
             * channels per connection.
             */
            private Integer size;

            /**
             * Duration to wait to obtain a channel if the cache size has been reached. If
             * 0, always create a new channel.
             */
            private Duration checkoutTimeout;

        }

        @Getter
        @Setter
        public static class Connection {

            /**
             * Connection factory cache mode.
             */
            private String mode = "CHANNEL";

            /**
             * Number of connections to cache. Only applies when mode is CONNECTION.
             */
            private Integer size;

        }

    }

    public enum ContainerType {

        /**
         * Container where the RabbitMQ consumer dispatches messages to an invoker thread.
         */
        SIMPLE,

        /**
         * Container where the listener is invoked directly on the RabbitMQ consumer
         * thread.
         */
        DIRECT

    }


    @Getter
    @Setter
    public static class Listener {

        /**
         * Listener container type.
         */
        private ContainerType type = ContainerType.SIMPLE;

        private final SimpleContainer simple = new SimpleContainer();

        private final DirectContainer direct = new DirectContainer();

    }

    @Getter
    @Setter
    public abstract static class AmqpContainer {

        /**
         * Whether to start the container automatically on startup.
         */
        private boolean autoStartup = true;

        /**
         * Acknowledge mode of container.
         */
        private Integer acknowledgeMode;

        /**
         * Maximum number of unacknowledged messages that can be outstanding at each
         * consumer.
         */
        private Integer prefetch;

        /**
         * Whether rejected deliveries are re-queued by default.
         */
        private Boolean defaultRequeueRejected;

        /**
         * How often idle container events should be published.
         */
        private Duration idleEventInterval;

        /**
         * Optional properties for a retry interceptor.
         */
        private final ListenerRetry retry = new ListenerRetry();


        public abstract boolean isMissingQueuesFatal();

    }

    /**
     * Configuration properties for {@code SimpleMessageListenerContainer}.
     */
    @Getter
    @Setter
    public static class SimpleContainer extends AmqpContainer {

        /**
         * Minimum number of listener invoker threads.
         */
        private Integer concurrency;

        /**
         * Maximum number of listener invoker threads.
         */
        private Integer maxConcurrency;

        /**
         * Number of messages to be processed between acks when the acknowledge mode is
         * AUTO. If larger than prefetch, prefetch will be increased to this value.
         */
        private Integer transactionSize;

        /**
         * Whether to fail if the queues declared by the container are not available on
         * the communications and/or whether to stop the container if one or more queues are
         * deleted at runtime.
         */
        private boolean missingQueuesFatal = true;

    }

    /**
     * Configuration properties for {@code DirectMessageListenerContainer}.
     */
    @Getter
    @Setter
    public static class DirectContainer extends AmqpContainer {

        /**
         * Number of consumers per queue.
         */
        private Integer consumersPerQueue;

        /**
         * Whether to fail if the queues declared by the container are not available on
         * the communications.
         */
        private boolean missingQueuesFatal = false;

    }

    @Getter
    @Setter
    public static class Template {

        private final Retry retry = new Retry();

        /**
         * Whether to enable mandatory messages.
         */
        private Boolean mandatory;

        /**
         * Timeout for `receive()` operations.
         */
        private Duration receiveTimeout;

        /**
         * Timeout for `sendAndReceive()` operations.
         */
        private Duration replyTimeout;

        /**
         * Name of the default exchange to use for send operations.
         */
        private String exchange = "";

        /**
         * Value of a default routing key to use for send operations.
         */
        private String routingKey = "";

        /**
         * Name of the default queue to receive messages from when none is specified
         * explicitly.
         */
        private String queue;

    }

    @Getter
    @Setter
    public static class Retry {

        /**
         * Whether publishing retries are enabled.
         */
        private boolean enabled;

        /**
         * Maximum number of attempts to deliver a message.
         */
        private int maxAttempts = 3;

        /**
         * Duration between the first and second attempt to deliver a message.
         */
        private Duration initialInterval = Duration.ofMillis(1000);

        /**
         * Multiplier to apply to the previous retry interval.
         */
        private double multiplier = 1.0;

        /**
         * Maximum duration between attempts.
         */
        private Duration maxInterval = Duration.ofMillis(10000);

    }

    @Getter
    @Setter
    public static class ListenerRetry extends Retry {

        /**
         * Whether retries are stateless or stateful.
         */
        private boolean stateless = true;
    }

    private static final class Address {

        private static final String PREFIX_AMQP = "amqp://";

        private static final int DEFAULT_PORT = 5672;

        private String host;

        private int port;

        private String username;

        private String password;

        private String virtualHost;

        private Address(String input) {
            input = input.trim();
            input = trimPrefix(input);
            input = parseUsernameAndPassword(input);
            input = parseVirtualHost(input);
            parseHostAndPort(input);
        }

        private String trimPrefix(String input) {
            if (input.startsWith(PREFIX_AMQP)) {
                input = input.substring(PREFIX_AMQP.length());
            }
            return input;
        }

        private String parseUsernameAndPassword(String input) {
            if (input.contains("@")) {
                String[] split = StringUtils.split(input, "@");
                if (split == null) return input;
                String creds = split[0];
                input = split[1];
                split = StringUtils.split(creds, ":");
                if (split != null) {
                    this.username = split[0];
                    if (split.length > 1) {
                        this.password = split[1];
                    }
                }
            }
            return input;
        }

        private String parseVirtualHost(String input) {
            int hostIndex = input.indexOf('/');
            if (hostIndex >= 0) {
                this.virtualHost = input.substring(hostIndex + 1);
                if (this.virtualHost.isEmpty()) {
                    this.virtualHost = "/";
                }
                input = input.substring(0, hostIndex);
            }
            return input;
        }

        private void parseHostAndPort(String input) {
            int portIndex = input.indexOf(':');
            if (portIndex == -1) {
                this.host = input;
                this.port = DEFAULT_PORT;
            } else {
                this.host = input.substring(0, portIndex);
                this.port = Integer.parseInt(input.substring(portIndex + 1));
            }
        }

    }

}
