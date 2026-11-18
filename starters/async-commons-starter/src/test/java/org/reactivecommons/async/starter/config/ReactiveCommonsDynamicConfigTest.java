package org.reactivecommons.async.starter.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.starter.broker.BrokerProvider;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;
import static reactor.test.StepVerifier.create;

@ExtendWith(MockitoExtension.class)
class ReactiveCommonsDynamicConfigTest {

    @Mock
    private BrokerProvider<?> provider;
    @Mock
    private HandlerResolver resolver;
    @Mock
    private DomainEventBus domainEventBus;
    @Mock
    private DirectAsyncGateway directAsyncGateway;

    private ConnectionManager manager;
    private DomainHandlers handlers;
    private ReactiveCommonsDynamicConfig config;

    @BeforeEach
    void setUp() {
        config = new ReactiveCommonsDynamicConfig();
        manager = new ConnectionManager();
        manager.addDomain(DEFAULT_DOMAIN, provider);
        handlers = new DomainHandlers();
        handlers.add(DEFAULT_DOMAIN, resolver);
    }

    // -------------------------------------------------------------------------
    // Helper: build a ReactiveCommonsDomainFeatures for DEFAULT_DOMAIN
    // -------------------------------------------------------------------------

    /**
     * The features of a domain: each one is enabled when it is in the set, and disabled otherwise.
     */
    private enum Capability {
        LISTEN_EVENTS, LISTEN_NOTIFICATIONS, LISTEN_COMMANDS, LISTEN_QUERIES, SEND_EVENTS, SEND_COMMANDS
    }

    private ReactiveCommonsDomainFeatures domainFeatures(Set<Capability> enabled) {
        var features = new ReactiveCommonsDomainFeatures();
        ReactiveCommonsFeatures f = features.withDomain(DEFAULT_DOMAIN);
        f.setListenEvents(enabled.contains(Capability.LISTEN_EVENTS));
        f.setListenNotificationEvents(enabled.contains(Capability.LISTEN_NOTIFICATIONS));
        f.setListenCommands(enabled.contains(Capability.LISTEN_COMMANDS));
        f.setListenQueries(enabled.contains(Capability.LISTEN_QUERIES));
        f.setSendEvents(enabled.contains(Capability.SEND_EVENTS));
        f.setSendCommands(enabled.contains(Capability.SEND_COMMANDS));
        return features;
    }

    private ReactiveCommonsDomainFeatures allFalse() {
        return domainFeatures(EnumSet.noneOf(Capability.class));
    }

    // -------------------------------------------------------------------------
    // Listener activation tests
    // -------------------------------------------------------------------------

    @Test
    void shouldActivateEventListenersWhenFlagIsTrue() {
        var features = domainFeatures(EnumSet.of(Capability.LISTEN_EVENTS));
        config.dynamicEventListenerActivator(manager, handlers, features);
        verify(provider).listenDomainEvents(resolver);
    }

    @Test
    void shouldNotActivateEventListenersWhenFlagIsFalse() {
        config.dynamicEventListenerActivator(manager, handlers, allFalse());
        verify(provider, never()).listenDomainEvents(resolver);
    }

    @Test
    void shouldActivateNotificationListenersWhenFlagIsTrue() {
        var features = domainFeatures(EnumSet.of(Capability.LISTEN_NOTIFICATIONS));
        config.dynamicNotificationListenerActivator(manager, handlers, features);
        verify(provider).listenNotificationEvents(resolver);
    }

    @Test
    void shouldNotActivateNotificationListenersWhenFlagIsFalse() {
        config.dynamicNotificationListenerActivator(manager, handlers, allFalse());
        verify(provider, never()).listenNotificationEvents(resolver);
    }

    @Test
    void shouldActivateCommandListenersWhenFlagIsTrue() {
        var features = domainFeatures(EnumSet.of(Capability.LISTEN_COMMANDS));
        config.dynamicCommandListenerActivator(manager, handlers, features);
        verify(provider).listenCommands(resolver);
    }

    @Test
    void shouldNotActivateCommandListenersWhenFlagIsFalse() {
        config.dynamicCommandListenerActivator(manager, handlers, allFalse());
        verify(provider, never()).listenCommands(resolver);
    }

    @Test
    void shouldActivateQueryListenersWhenFlagIsTrue() {
        var features = domainFeatures(EnumSet.of(Capability.LISTEN_QUERIES));
        config.dynamicQueryListenerActivator(manager, handlers, features);
        verify(provider).listenQueries(resolver);
    }

    @Test
    void shouldNotActivateQueryListenersWhenFlagIsFalse() {
        config.dynamicQueryListenerActivator(manager, handlers, allFalse());
        verify(provider, never()).listenQueries(resolver);
    }

    @Test
    void shouldActivateQueueListenersWhenFlagIsTrue() {
        var features = new ReactiveCommonsDomainFeatures();
        features.withDomain(DEFAULT_DOMAIN).setListenQueues(true);
        config.dynamicQueueListenerActivator(manager, handlers, features);
        verify(provider).listenQueues(resolver);
    }

    @Test
    void shouldNotActivateQueueListenersWhenFlagIsFalse() {
        config.dynamicQueueListenerActivator(manager, handlers, allFalse());
        verify(provider, never()).listenQueues(resolver);
    }

    @Test
    void shouldActivateTopicListenersWhenFlagIsTrue() {
        var features = new ReactiveCommonsDomainFeatures();
        features.withDomain(DEFAULT_DOMAIN).setListenTopics(true);
        config.dynamicTopicListenerActivator(manager, handlers, features);
        verify(provider).listenTopics(resolver);
    }

    @Test
    void shouldNotActivateTopicListenersWhenFlagIsFalse() {
        config.dynamicTopicListenerActivator(manager, handlers, allFalse());
        verify(provider, never()).listenTopics(resolver);
    }

    @Test
    void shouldActivateAllListenersWhenAllFlagsAreTrue() {
        var features = domainFeatures(EnumSet.of(Capability.LISTEN_EVENTS, Capability.LISTEN_NOTIFICATIONS,
                Capability.LISTEN_COMMANDS, Capability.LISTEN_QUERIES));

        config.dynamicEventListenerActivator(manager, handlers, features);
        config.dynamicNotificationListenerActivator(manager, handlers, features);
        config.dynamicCommandListenerActivator(manager, handlers, features);
        config.dynamicQueryListenerActivator(manager, handlers, features);

        verify(provider).listenDomainEvents(resolver);
        verify(provider).listenNotificationEvents(resolver);
        verify(provider).listenCommands(resolver);
        verify(provider).listenQueries(resolver);
    }

    @Test
    void shouldActivateNoListenersWhenAllFlagsAreFalse() {
        var features = allFalse();

        config.dynamicEventListenerActivator(manager, handlers, features);
        config.dynamicNotificationListenerActivator(manager, handlers, features);
        config.dynamicCommandListenerActivator(manager, handlers, features);
        config.dynamicQueryListenerActivator(manager, handlers, features);

        verify(provider, never()).listenDomainEvents(resolver);
        verify(provider, never()).listenNotificationEvents(resolver);
        verify(provider, never()).listenCommands(resolver);
        verify(provider, never()).listenQueries(resolver);
    }

    @Test
    void shouldActivateListenersOnMultipleDomainsWhenFlagIsTrue() {
        manager.addDomain("other-domain", provider);
        handlers.add("other-domain", resolver);

        var features = new ReactiveCommonsDomainFeatures();
        features.withDomain(DEFAULT_DOMAIN).setListenEvents(true);
        features.withDomain("other-domain").setListenEvents(true);

        config.dynamicEventListenerActivator(manager, handlers, features);

        verify(provider, times(2)).listenDomainEvents(resolver);
    }

    @Test
    void shouldOnlyActivateListenerForConfiguredDomainWhenOtherDomainFlagIsFalse() {
        manager.addDomain("other-domain", provider);
        handlers.add("other-domain", resolver);

        var features = new ReactiveCommonsDomainFeatures();
        features.withDomain(DEFAULT_DOMAIN).setListenEvents(true);
        features.withDomain("other-domain"); // listenEvents=false by default

        config.dynamicEventListenerActivator(manager, handlers, features);

        verify(provider, times(1)).listenDomainEvents(resolver);
    }

    // -------------------------------------------------------------------------
    // Sender bean tests
    // -------------------------------------------------------------------------

    @Test
    void shouldCreateRealDomainEventBusWhenSendEventsIsTrue() {
        when(provider.getDomainBus()).thenReturn(domainEventBus);
        var features = domainFeatures(EnumSet.of(Capability.SEND_EVENTS));

        DomainEventBus bus = config.dynamicDomainEventBus(manager, features);

        assertThat(bus).isNotNull();
        verify(provider).getDomainBus();
    }

    @Test
    void shouldReturnDisabledDomainEventBusWhenSendEventsIsFalse() {
        var features = allFalse();

        DomainEventBus bus = config.dynamicDomainEventBus(manager, features);

        assertThat(bus).isNotNull();
        verify(provider, never()).getDomainBus();
    }

    @Test
    void disabledDomainEventBusShouldEmitErrorOnUse() {
        var features = allFalse();
        DomainEventBus bus = config.dynamicDomainEventBus(manager, features);

        create(bus.emit(new org.reactivecommons.api.domain.DomainEvent<>("test", "1", "data")))
                .expectErrorSatisfies(ex -> assertThat(ex)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("sendEvents"))
                .verify();
    }

    @Test
    void shouldCreateRealDirectAsyncGatewayWhenSendCommandsIsTrue() {
        when(provider.getDirectAsyncGateway()).thenReturn(directAsyncGateway);
        var features = domainFeatures(EnumSet.of(Capability.SEND_COMMANDS));

        DirectAsyncGateway gateway = config.dynamicDirectAsyncGateway(manager, features);

        assertThat(gateway).isNotNull();
        verify(provider).getDirectAsyncGateway();
    }

    @Test
    void shouldCreateDirectAsyncGatewayEvenWhenSendCommandsIsFalse() {
        // The gateway is always registered in the map regardless of sendCommands flag.
        // Conditional logging only; the actual put is unconditional.
        var features = allFalse();

        DirectAsyncGateway gateway = config.dynamicDirectAsyncGateway(manager, features);

        assertThat(gateway).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Default flags for a new ReactiveCommonsFeatures instance
    // -------------------------------------------------------------------------

    @Test
    void newReactiveCommonsFeaturesShouldHaveAllFlagsFalse() {
        ReactiveCommonsFeatures features = new ReactiveCommonsFeatures();
        assertThat(features.isListenEvents()).isFalse();
        assertThat(features.isListenNotificationEvents()).isFalse();
        assertThat(features.isListenCommands()).isFalse();
        assertThat(features.isListenQueries()).isFalse();
        assertThat(features.isListenQueues()).isFalse();
        assertThat(features.isListenTopics()).isFalse();
        assertThat(features.isSendEvents()).isFalse();
        assertThat(features.isSendCommands()).isFalse();
    }

    @Test
    void withDomainShouldReturnSameInstanceOnSubsequentCalls() {
        var features = new ReactiveCommonsDomainFeatures();
        ReactiveCommonsFeatures first = features.withDomain(DEFAULT_DOMAIN);
        ReactiveCommonsFeatures second = features.withDomain(DEFAULT_DOMAIN);
        assertThat(first).isSameAs(second);
    }

    @Test
    void ofDomainShouldReturnConfiguredFeatures() {
        var features = new ReactiveCommonsDomainFeatures();
        features.withDomain(DEFAULT_DOMAIN).setListenEvents(true);
        assertThat(features.ofDomain(DEFAULT_DOMAIN).isListenEvents()).isTrue();
    }
}
