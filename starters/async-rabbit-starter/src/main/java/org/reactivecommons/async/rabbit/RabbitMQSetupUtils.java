package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.DLQDiscardNotifier;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageNotifier;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.spring.RabbitPropertiesBase;
import org.springframework.boot.context.properties.PropertyMapper;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.ChannelPool;
import reactor.rabbitmq.ChannelPoolFactory;
import reactor.rabbitmq.ChannelPoolOptions;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;
import reactor.rabbitmq.Utils;
import reactor.util.retry.Retry;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Arrays;
import java.util.logging.Level;

@Log
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RabbitMQSetupUtils {
    private static final String LISTENER_TYPE = "listener";
    private static final String TOPOLOGY_TYPE = "topology";
    private static final String SENDER_TYPE = "sender";
    private static final String DEFAULT_PROTOCOL;
    public static final int START_INTERVAL = 300;
    public static final int MAX_BACKOFF_INTERVAL = 3000;

    static {
        String protocol = "TLSv1.1";
        try {
            String[] protocols = SSLContext.getDefault().getSupportedSSLParameters().getProtocols();
            for (String prot : protocols) {
                if ("TLSv1.2".equals(prot)) {
                    protocol = "TLSv1.2";
                    break;
                }
            }
        } catch (NoSuchAlgorithmException e) {
            // nothing
        }
        DEFAULT_PROTOCOL = protocol;
    }

    @SneakyThrows
    public static ConnectionFactoryProvider connectionFactoryProvider(RabbitProperties properties) {
        final ConnectionFactory factory = new ConnectionFactory();
        PropertyMapper map = PropertyMapper.get();
        map.from(properties::determineHost).whenNonNull().to(factory::setHost);
        map.from(properties::determinePort).to(factory::setPort);
        map.from(properties::determineUsername).whenNonNull().to(factory::setUsername);
        map.from(properties::determinePassword).whenNonNull().to(factory::setPassword);
        map.from(properties::determineVirtualHost).whenNonNull().to(factory::setVirtualHost);
        factory.useNio();
        setUpSSL(factory, properties);
        return () -> factory;
    }


    public static ReactiveMessageSender createMessageSender(ConnectionFactoryProvider provider,
                                                            AsyncProps props,
                                                            MessageConverter converter,
                                                            UnroutableMessageNotifier unroutableMessageNotifier) {
        final Sender sender = RabbitFlux.createSender(reactiveCommonsSenderOptions(props.getAppName(), provider,
                props.getConnectionProperties()));
        return new ReactiveMessageSender(sender, props.getAppName(), converter, new TopologyCreator(sender,
                props.getQueueType()),
                props.getMandatory(), unroutableMessageNotifier
        );
    }

    public static ReactiveMessageListener createMessageListener(ConnectionFactoryProvider provider, AsyncProps props) {
        final Mono<Connection> connection =
                createConnectionMono(provider.getConnectionFactory(), props.getAppName(), LISTENER_TYPE);
        final Receiver receiver = RabbitFlux.createReceiver(new ReceiverOptions().connectionMono(connection));
        final Sender sender = RabbitFlux.createSender(new SenderOptions().connectionMono(connection));

        return new ReactiveMessageListener(receiver,
                new TopologyCreator(sender, props.getQueueType()),
                props.getFlux().getMaxConcurrency(),
                props.getPrefetchCount());
    }

    public static TopologyCreator createTopologyCreator(AsyncProps props) {
        ConnectionFactoryProvider provider = connectionFactoryProvider(props.getConnectionProperties());
        final Mono<Connection> connection = createConnectionMono(provider.getConnectionFactory(),
                props.getAppName(), TOPOLOGY_TYPE);
        final Sender sender = RabbitFlux.createSender(new SenderOptions().connectionMono(connection));
        return new TopologyCreator(sender, props.getQueueType());
    }

    public static DiscardNotifier createDiscardNotifier(ReactiveMessageSender sender, AsyncProps props,
                                                        BrokerConfig brokerConfig, MessageConverter converter) {
        DomainEventBus appDomainEventBus = new RabbitDomainEventBus(sender,
                props.getBrokerConfigProps().getDomainEventsExchangeName(), brokerConfig);
        return new DLQDiscardNotifier(appDomainEventBus, converter);
    }

    private static SenderOptions reactiveCommonsSenderOptions(String appName, ConnectionFactoryProvider provider,
                                                              RabbitProperties rabbitProperties) {
        final Mono<Connection> senderConnection = createConnectionMono(provider.getConnectionFactory(), appName,
                SENDER_TYPE);
        final ChannelPoolOptions channelPoolOptions = new ChannelPoolOptions();
        final PropertyMapper map = PropertyMapper.get();

        map.from(rabbitProperties.getCache().getChannel()::getSize).whenNonNull()
                .to(channelPoolOptions::maxCacheSize);

        final ChannelPool channelPool = ChannelPoolFactory.createChannelPool(
                senderConnection,
                channelPoolOptions
        );

        return new SenderOptions()
                .channelPool(channelPool)
                .resourceManagementChannelMono(channelPool.getChannelMono()
                        .transform(Utils::cache));
    }

    private static Mono<Connection> createConnectionMono(ConnectionFactory factory, String connectionPrefix,
                                                         String connectionType) {
        log.info("Creating connection mono to RabbitMQ Broker in host '" + factory.getHost() + "' with " +
                "type: " + connectionType);
        return Mono.fromCallable(() -> factory.newConnection(connectionPrefix + " " + connectionType))
                .doOnError(err ->
                        log.log(Level.SEVERE, "Error creating connection to RabbitMQ Broker in host '" +
                                factory.getHost() + "'. Starting retry process...", err)
                )
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(START_INTERVAL))
                        .maxBackoff(Duration.ofMillis(MAX_BACKOFF_INTERVAL)))
                .cache();
    }

    // SSL based on RabbitConnectionFactoryBean
    // https://github.com/spring-projects/spring-amqp/blob/main/spring-rabbit/src/main/java/org/springframework/amqp/rabbit/connection/RabbitConnectionFactoryBean.java

    private static void setUpSSL(ConnectionFactory factory, RabbitProperties properties)
            throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException,
            CertificateException, IOException {
        var ssl = properties.getSsl();
        if (ssl != null && ssl.isEnabled()) {
            var keyManagers = configureKeyManagers(ssl);
            var trustManagers = configureTrustManagers(ssl);
            var secureRandom = SecureRandom.getInstanceStrong();

            if (log.isLoggable(Level.FINE)) {
                log.fine("Initializing SSLContext with KM: " + Arrays.toString(keyManagers) +
                        ", TM: " + Arrays.toString(trustManagers) + ", random: " + secureRandom);
            }
            var context = createSSLContext(ssl);
            context.init(keyManagers, trustManagers, secureRandom);
            factory.useSslProtocol(context);

            logDetails(trustManagers);

            if (ssl.isVerifyHostname()) {
                factory.enableHostnameVerification();
            }
        }
    }

    private static KeyManager[] configureKeyManagers(RabbitPropertiesBase.Ssl ssl) throws KeyStoreException,
            IOException,
            NoSuchAlgorithmException,
            CertificateException, UnrecoverableKeyException {
        KeyManager[] keyManagers = null;
        if (ssl.getKeyStore() != null) {
            var ks = KeyStore.getInstance(ssl.getKeyStoreType());
            char[] keyPassphrase = null;
            if (ssl.getKeyStorePassword() != null) {
                keyPassphrase = ssl.getKeyStorePassword().toCharArray();
            }
            try (var inputStream = new FileInputStream(ssl.getKeyStore())) {
                ks.load(inputStream, keyPassphrase);
            }
            var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, keyPassphrase);
            keyManagers = kmf.getKeyManagers();
        }
        return keyManagers;
    }

    private static TrustManager[] configureTrustManagers(RabbitPropertiesBase.Ssl ssl)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
        KeyStore tks = null;
        if (ssl.getTrustStore() != null) {
            tks = KeyStore.getInstance(ssl.getTrustStoreType());
            char[] trustPassphrase = null;
            if (ssl.getTrustStorePassword() != null) {
                trustPassphrase = ssl.getTrustStorePassword().toCharArray();
            }
            try (InputStream inputStream = new FileInputStream(ssl.getTrustStore())) {
                tks.load(inputStream, trustPassphrase);
            }
        }

        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(tks);
        return tmf.getTrustManagers();
    }

    private static SSLContext createSSLContext(RabbitPropertiesBase.Ssl ssl) throws NoSuchAlgorithmException {
        return SSLContext.getInstance(ssl.getAlgorithm() != null ? ssl.getAlgorithm() : DEFAULT_PROTOCOL);
    }

    private static void logDetails(TrustManager[] managers) {
        var found = false;
        for (var trustManager : managers) {
            if (trustManager instanceof X509TrustManager x509TrustManager) {
                found = true;
                log.info("Loaded " + x509TrustManager.getAcceptedIssuers().length + " accepted issuers for rabbitmq");
            }
        }
        if (!found) {
            log.warning("No X509TrustManager found in the truststore.");
        }
    }

}
