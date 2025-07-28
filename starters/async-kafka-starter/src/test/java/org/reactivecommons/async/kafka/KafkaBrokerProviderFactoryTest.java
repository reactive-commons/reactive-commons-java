package org.reactivecommons.async.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.broker.BrokerProviderFactory;
import org.reactivecommons.async.starter.broker.DiscardProvider;
import org.springframework.boot.ssl.SslBundles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class KafkaBrokerProviderFactoryTest {
    private final ReactiveReplyRouter router = new ReactiveReplyRouter();
    @Mock
    private KafkaJacksonMessageConverter converter;
    @Mock
    private MeterRegistry meterRegistry;
    @Mock
    private CustomReporter errorReporter;
    @Mock
    private KafkaCustomizations customizations;
    @Mock
    private SslBundles sslBundles;

    private BrokerProviderFactory<AsyncKafkaProps> providerFactory;

    @BeforeEach
    void setUp() {
        providerFactory = new KafkaBrokerProviderFactory(router, converter, meterRegistry, errorReporter,
                customizations, sslBundles);
    }

    @Test
    void shouldReturnBrokerType() {
        // Arrange
        // Act
        String brokerType = providerFactory.getBrokerType();
        // Assert
        assertEquals("kafka", brokerType);
    }

    @Test
    void shouldReturnCreateDiscardProvider() {
        // Arrange
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.setCheckExistingTopics(false);
        // Act
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Assert
        assertThat(discardProvider).isInstanceOf(KafkaDiscardProvider.class);
    }

    @Test
    void shouldReturnBrokerProvider() {
        // Arrange
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.setCheckExistingTopics(false);
        DiscardProvider discardProvider = providerFactory.getDiscardProvider(props);
        // Act
        BrokerProvider<AsyncKafkaProps> brokerProvider = providerFactory.getProvider("domain", props, discardProvider);
        // Assert
        assertThat(brokerProvider).isInstanceOf(KafkaBrokerProvider.class);
    }
}
