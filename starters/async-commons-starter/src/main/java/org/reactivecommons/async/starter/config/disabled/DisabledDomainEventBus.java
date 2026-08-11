package org.reactivecommons.async.starter.config.disabled;

import org.reactivecommons.api.domain.DomainEventBus;
import reactor.core.publisher.Mono;

public class DisabledDomainEventBus implements DomainEventBus {
    private static final String SEND_EVENTS_DISABLED =
            "sendEvents feature is disabled in ReactiveCommonsFeatures. " +
                    "Set sendEvents=true to publish domain events.";

    @Override
    public <T> org.reactivestreams.Publisher<Void> emit(org.reactivecommons.api.domain.DomainEvent<T> event) {
        return Mono.error(new IllegalStateException(SEND_EVENTS_DISABLED));
    }

    @Override
    public <T> org.reactivestreams.Publisher<Void> emit(String domain,
                                                        org.reactivecommons.api.domain.DomainEvent<T> event) {
        return Mono.error(new IllegalStateException(SEND_EVENTS_DISABLED));
    }

    @Override
    public org.reactivestreams.Publisher<Void> emit(io.cloudevents.CloudEvent event) {
        return Mono.error(new IllegalStateException(SEND_EVENTS_DISABLED));
    }

    @Override
    public org.reactivestreams.Publisher<Void> emit(String domain, io.cloudevents.CloudEvent event) {
        return Mono.error(new IllegalStateException(SEND_EVENTS_DISABLED));
    }

    @Override
    public org.reactivestreams.Publisher<Void> emit(org.reactivecommons.api.domain.RawMessage event) {
        return Mono.error(new IllegalStateException(SEND_EVENTS_DISABLED));
    }

    @Override
    public org.reactivestreams.Publisher<Void> emit(String domain,
                                                    org.reactivecommons.api.domain.RawMessage event) {
        return Mono.error(new IllegalStateException(SEND_EVENTS_DISABLED));
    }
}