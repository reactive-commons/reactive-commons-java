package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
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

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

@Log
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RabbitMQSetupUtils {
    private static final String SHARED_TYPE = "shared";
    public static final int START_INTERVAL = 300;
    public static final int MAX_BACKOFF_INTERVAL = 3000;

    private static final ConcurrentMap<AsyncProps, ConnectionFactory> FACTORY_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ConnectionFactory, Mono<Connection>> CONNECTION_CACHE = new ConcurrentHashMap<>();

    public static ConnectionFactoryProvider connectionFactoryProvider(AsyncProps asyncProps,
                                                                      ConnectionFactoryCustomizer cfCustomizer) {
        final ConnectionFactory factory = FACTORY_CACHE.computeIfAbsent(asyncProps, props -> {
            try {
                RabbitProperties rabbitProperties = props.getConnectionProperties();
                ConnectionFactory newFactory = new ConnectionFactory();
                PropertyMapper map = PropertyMapper.get();
                map.from(rabbitProperties::determineHost).to(newFactory::setHost);
                map.from(rabbitProperties::determinePort).to(newFactory::setPort);
                map.from(rabbitProperties::determineUsername).to(newFactory::setUsername);
                map.from(rabbitProperties::determinePassword).to(newFactory::setPassword);
                map.from(rabbitProperties::determineVirtualHost).to(newFactory::setVirtualHost);
                newFactory.netty();
                setUpSSL(newFactory, rabbitProperties);
                return cfCustomizer.customize(newFactory, props);
            } catch (Exception e) {
                throw new RuntimeException("Error creating ConnectionFactory: ", e);
            }
        });
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
                createConnectionMono(provider.getConnectionFactory(), props.getAppName());
        final Receiver receiver = RabbitFlux.createReceiver(new ReceiverOptions().connectionMono(connection));
        final Sender sender = RabbitFlux.createSender(new SenderOptions().connectionMono(connection));

        return new ReactiveMessageListener(receiver,
                new TopologyCreator(sender, props.getQueueType()),
                props.getFlux().getMaxConcurrency(),
                props.getPrefetchCount());
    }

    public static TopologyCreator createTopologyCreator(AsyncProps props, ConnectionFactoryCustomizer cfCustomizer) {
        ConnectionFactoryProvider provider = connectionFactoryProvider(props, cfCustomizer);
        final Mono<Connection> connection = createConnectionMono(provider.getConnectionFactory(), props.getAppName());
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
        final Mono<Connection> senderConnection = createConnectionMono(provider.getConnectionFactory(), appName);
        final ChannelPoolOptions channelPoolOptions = new ChannelPoolOptions();
        final PropertyMapper map = PropertyMapper.get();

        map.from(rabbitProperties.getCache().getChannel()::getSize).to(channelPoolOptions::maxCacheSize);

        final ChannelPool channelPool = ChannelPoolFactory.createChannelPool(
                senderConnection,
                channelPoolOptions
        );

        return new SenderOptions()
                .channelPool(channelPool)
                .resourceManagementChannelMono(channelPool.getChannelMono()
                        .transform(Utils::cache));
    }

    private static Mono<Connection> createConnectionMono(ConnectionFactory factory, String appName) {
        return CONNECTION_CACHE.computeIfAbsent(factory, f -> {
            log.info("Creating connection mono to RabbitMQ Broker in host '" + f.getHost() + "'");
            return Mono.fromCallable(() -> f.newConnection(
                            appName + "-" + InstanceIdentifier.getInstanceId(SHARED_TYPE, "")
                    ))
                    .doOnError(err ->
                            log.log(Level.SEVERE, "Error creating connection to RabbitMQ Broker in host '"
                                    + f.getHost() + "'. Starting retry process...", err)
                    )
                    .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(START_INTERVAL))
                            .maxBackoff(Duration.ofMillis(MAX_BACKOFF_INTERVAL)))
                    .cache();
        });
    }

    // SSL based on RabbitConnectionFactoryBean
    // https://github.com/spring-projects/spring-amqp/blob/main/spring-rabbit/src/main/java/org/springframework/amqp/rabbit/connection/RabbitConnectionFactoryBean.java
    // Adapted for Netty: https://www.rabbitmq.com/client-libraries/java-api-guide#netty

    private static void setUpSSL(ConnectionFactory factory, RabbitProperties properties)
            throws NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException,
            CertificateException, IOException {
        var ssl = properties.getSsl();
        if (ssl != null && ssl.isEnabled()) {
            SslContextBuilder sslContextBuilder = SslContextBuilder.forClient();

            // Configure TrustManager
            var trustManagerFactory = configureTrustManagerFactory(ssl);
            sslContextBuilder.trustManager(trustManagerFactory);

            // Configure KeyManager if keystore is provided
            if (ssl.getKeyStore() != null) {
                var keyManagerFactory = configureKeyManagerFactory(ssl);
                sslContextBuilder.keyManager(keyManagerFactory);
            }

            // Set SSL protocol if specified
            if (ssl.getAlgorithm() != null) {
                log.info("Using SSL protocol: " + ssl.getAlgorithm());
            }

            SslContext sslContext = sslContextBuilder.build();
            factory.netty().sslContext(sslContext);

            if (ssl.isVerifyHostname()) {
                factory.enableHostnameVerification();
            }
        }
    }

    private static KeyManagerFactory configureKeyManagerFactory(RabbitPropertiesBase.Ssl ssl)
            throws KeyStoreException, IOException, NoSuchAlgorithmException,
            CertificateException, UnrecoverableKeyException {
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
        return kmf;
    }

    private static TrustManagerFactory configureTrustManagerFactory(RabbitPropertiesBase.Ssl ssl)
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
        return tmf;
    }

}
