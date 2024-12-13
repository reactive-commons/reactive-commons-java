package org.reactivecommons.async.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import org.reactivecommons.async.kafka.health.KafkaReactiveHealthIndicator;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundles;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.sound.midi.Receiver;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@ExtendWith(MockitoExtension.class)
class KafkaBrokerProviderTest {
    private final AsyncKafkaProps props = new AsyncKafkaProps();
    private final SslBundles sslBundles = new DefaultSslBundleRegistry();
    private final KafkaCustomizations customizations = new KafkaCustomizations();
    @Mock
    private ReactiveMessageListener listener;
    @Mock
    private TopologyCreator creator;
    @Mock
    private HandlerResolver handlerResolver;
    @Mock
    private KafkaJacksonMessageConverter messageConverter;
    @Mock
    private CustomReporter customReporter;
    @Mock
    private Receiver receiver;
    @Mock
    private ReactiveReplyRouter router;
    @Mock
    private MeterRegistry meterRegistry;
    @Mock
    private ReactiveMessageSender sender;
    @Mock
    private DiscardNotifier discardNotifier;
    @Mock
    private KafkaReactiveHealthIndicator healthIndicator;


    private BrokerProvider<AsyncKafkaProps> brokerProvider;


    @BeforeEach
    public void init() {
        props.setAppName("test");
        brokerProvider = new KafkaBrokerProvider(DEFAULT_DOMAIN,
                props,
                router,
                messageConverter,
                meterRegistry,
                customReporter,
                healthIndicator,
                listener,
                sender,
                discardNotifier,
                creator,
                customizations,
                sslBundles);
    }

    @Test
    void shouldCreateDomainEventBus() {
        // Act
        DomainEventBus domainBus = brokerProvider.getDomainBus();
        // Assert
        assertThat(domainBus).isExactlyInstanceOf(KafkaDomainEventBus.class);
    }

    @Test
    void shouldCreateDirectAsyncGateway() {
        // Act
        DirectAsyncGateway domainBus = brokerProvider.getDirectAsyncGateway(handlerResolver);
        // Assert
        assertThat(domainBus).isExactlyInstanceOf(KafkaDirectAsyncGateway.class);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldListenDomainEvents() {
        List mockedListeners = spy(List.of());
        when(mockedListeners.isEmpty()).thenReturn(false);
        when(handlerResolver.getEventListeners()).thenReturn(mockedListeners);
        when(creator.createTopics(any())).thenReturn(Mono.empty());
        when(listener.getMaxConcurrency()).thenReturn(1);
        when(listener.listen(any(String.class), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenDomainEvents(handlerResolver);
        // Assert
        verify(listener, times(1)).listen(any(String.class), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldListenNotificationEvents() {
        List mockedListeners = spy(List.of());
        when(mockedListeners.isEmpty()).thenReturn(false);
        when(handlerResolver.getNotificationListeners()).thenReturn(mockedListeners);
        when(creator.createTopics(any())).thenReturn(Mono.empty());
        when(listener.getMaxConcurrency()).thenReturn(1);
        when(listener.listen(any(String.class), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenNotificationEvents(handlerResolver);
        // Assert
        verify(listener, times(1)).listen(any(String.class), any());
    }

    @Test
    void shouldProxyHealthCheck() {
        when(healthIndicator.health()).thenReturn(Mono.fromSupplier(() -> RCHealth.builder().up().build()));
        // Act
        Mono<RCHealth> flow = brokerProvider.healthCheck();
        // Assert
        StepVerifier.create(flow)
                .expectNextMatches(health -> health.getStatus().equals(RCHealth.Status.UP))
                .verifyComplete();
    }
}
