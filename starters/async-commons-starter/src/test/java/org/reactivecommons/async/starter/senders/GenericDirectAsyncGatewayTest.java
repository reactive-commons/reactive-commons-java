package org.reactivecommons.async.starter.senders;

import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.From;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@ExtendWith(MockitoExtension.class)
class GenericDirectAsyncGatewayTest {

    public static final String DOMAIN_2 = "domain2";

    @Mock
    private DirectAsyncGateway directAsyncGateway1;

    @Mock
    private DirectAsyncGateway directAsyncGateway2;

    @Mock
    private CloudEvent cloudEvent;

    @Mock
    private Command<?> command;

    @Mock
    private AsyncQuery<?> asyncQuery;

    @Mock
    private From from;

    private final long delayMillis = 100L;

    private GenericDirectAsyncGateway genericDirectAsyncGateway;

    @BeforeEach
    void setUp() {
        ConcurrentHashMap<String, DirectAsyncGateway> directAsyncGateways = new ConcurrentHashMap<>();
        directAsyncGateways.put(DEFAULT_DOMAIN, directAsyncGateway1);
        directAsyncGateways.put(DOMAIN_2, directAsyncGateway2);
        genericDirectAsyncGateway = new GenericDirectAsyncGateway(directAsyncGateways);
    }

    @Test
    void shouldSendCommandWithDefaultDomain() {
        when(directAsyncGateway1.sendCommand(command, "target")).thenReturn(Mono.empty());
        Mono<Void> flow = genericDirectAsyncGateway.sendCommand(command, "target");
        StepVerifier.create(flow).verifyComplete();
        verify(directAsyncGateway1).sendCommand(command, "target");
    }

    @Test
    void shouldSendCommandWithDefaultDomainWithDelay() {
        when(directAsyncGateway1.sendCommand(command, "target", delayMillis)).thenReturn(Mono.empty());
        Mono<Void> flow = genericDirectAsyncGateway.sendCommand(command, "target", delayMillis);
        StepVerifier.create(flow).verifyComplete();
        verify(directAsyncGateway1).sendCommand(command, "target", delayMillis);
    }

    @Test
    void shouldSendCommandWithSpecificDomain() {
        when(directAsyncGateway2.sendCommand(command, "target")).thenReturn(Mono.empty());
        Mono<Void> flow = genericDirectAsyncGateway.sendCommand(command, "target", DOMAIN_2);
        StepVerifier.create(flow).verifyComplete();
        verify(directAsyncGateway2).sendCommand(command, "target");
    }

    @Test
    void shouldSendCommandWithSpecificDomainWithDelay() {
        when(directAsyncGateway2.sendCommand(command, "target", delayMillis)).thenReturn(Mono.empty());
        Mono<Void> flow = genericDirectAsyncGateway.sendCommand(command, "target", delayMillis, DOMAIN_2);
        StepVerifier.create(flow).verifyComplete();
        verify(directAsyncGateway2).sendCommand(command, "target", delayMillis);
    }

    @Test
    void shouldSendCloudEventWithDefaultDomain() {
        when(directAsyncGateway1.sendCommand(cloudEvent, "target")).thenReturn(Mono.empty());
        Mono<Void> flow = genericDirectAsyncGateway.sendCommand(cloudEvent, "target");
        StepVerifier.create(flow).verifyComplete();
        verify(directAsyncGateway1).sendCommand(cloudEvent, "target");
    }

    @Test
    void shouldSendCloudEventWithDefaultDomainWithDelay() {
        when(directAsyncGateway1.sendCommand(cloudEvent, "target", delayMillis)).thenReturn(Mono.empty());
        Mono<Void> flow = genericDirectAsyncGateway.sendCommand(cloudEvent, "target", delayMillis);
        StepVerifier.create(flow).verifyComplete();
        verify(directAsyncGateway1).sendCommand(cloudEvent, "target", delayMillis);
    }

    @Test
    void shouldSendCloudEventWithSpecificDomain() {
        when(directAsyncGateway2.sendCommand(cloudEvent, "target")).thenReturn(Mono.empty());
        Mono<Void> flow = genericDirectAsyncGateway.sendCommand(cloudEvent, "target", DOMAIN_2);
        StepVerifier.create(flow).verifyComplete();
        verify(directAsyncGateway2).sendCommand(cloudEvent, "target");
    }

    @Test
    void shouldSendCloudEventWithSpecificDomainWithDelay() {
        when(directAsyncGateway2.sendCommand(cloudEvent, "target", delayMillis)).thenReturn(Mono.empty());
        Mono<Void> flow = genericDirectAsyncGateway.sendCommand(cloudEvent, "target", delayMillis, DOMAIN_2);
        StepVerifier.create(flow).verifyComplete();
        verify(directAsyncGateway2).sendCommand(cloudEvent, "target", delayMillis);
    }

    @Test
    void shouldRequestReplyWithDefaultDomain() {
        when(directAsyncGateway1.requestReply(asyncQuery, "target", String.class)).thenReturn(Mono.just("response"));
        Mono<String> flow = genericDirectAsyncGateway.requestReply(asyncQuery, "target", String.class);
        StepVerifier.create(flow).expectNext("response").verifyComplete();
        verify(directAsyncGateway1).requestReply(asyncQuery, "target", String.class);
    }


    @Test
    void shouldRequestReplyWithDefaultDomainCloudEvent() {
        when(directAsyncGateway1.requestReply(cloudEvent, "target", CloudEvent.class)).thenReturn(Mono.just(cloudEvent));
        Mono<CloudEvent> flow = genericDirectAsyncGateway.requestReply(cloudEvent, "target", CloudEvent.class);
        StepVerifier.create(flow).expectNext(cloudEvent).verifyComplete();
        verify(directAsyncGateway1).requestReply(cloudEvent, "target", CloudEvent.class);
    }

    @Test
    void shouldRequestReplyWithSpecificDomain() {
        when(directAsyncGateway2.requestReply(asyncQuery, "target", String.class)).thenReturn(Mono.just("response"));
        Mono<String> flow = genericDirectAsyncGateway.requestReply(asyncQuery, "target", String.class, DOMAIN_2);
        StepVerifier.create(flow).expectNext("response").verifyComplete();
        verify(directAsyncGateway2).requestReply(asyncQuery, "target", String.class);
    }

    @Test
    void shouldRequestReplyWithSpecificDomainCloudEvent() {
        when(directAsyncGateway2.requestReply(cloudEvent, "target", CloudEvent.class)).thenReturn(Mono.just(cloudEvent));
        Mono<CloudEvent> flow = genericDirectAsyncGateway.requestReply(cloudEvent, "target", CloudEvent.class, DOMAIN_2);
        StepVerifier.create(flow).expectNext(cloudEvent).verifyComplete();
        verify(directAsyncGateway2).requestReply(cloudEvent, "target", CloudEvent.class);
    }

    @Test
    void shouldReplyWithDefaultDomain() {
        when(directAsyncGateway1.reply("response", from)).thenReturn(Mono.empty());
        Mono<Void> flow = genericDirectAsyncGateway.reply("response", from);
        StepVerifier.create(flow).verifyComplete();
        verify(directAsyncGateway1).reply("response", from);
    }
}