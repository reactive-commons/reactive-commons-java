package org.reactivecommons.async.parent.ext;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.parent.communications.Message;
import org.reactivecommons.async.parent.ext.CustomErrorReporter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;

public class CustomErrorReporterTest {

    private final PublisherProbe<Void> commandProbe = PublisherProbe.empty();
    private final PublisherProbe<Void> eventProbe = PublisherProbe.empty();
    private final PublisherProbe<Void> queryProbe = PublisherProbe.empty();

    private final TestCustomReporter customReporter = new TestCustomReporter();
    private final Message rawMessage = mock(Message.class);

    @Test
    public void reportErrorCommand() {
        final Command<Object> message = new Command<>("", "", null);

        assertReportError(message);

        commandProbe.assertWasSubscribed();
    }

    @Test
    public void testReportErrorEvent() {
        final DomainEvent<Object> message = new DomainEvent<>("", "", null);

        assertReportError(message);

        eventProbe.assertWasSubscribed();
    }

    @Test
    public void testReportErrorQuery() {
        final AsyncQuery<Object> message = new AsyncQuery<>("", null);

        assertReportError(message);

        queryProbe.assertWasSubscribed();
    }

    @Test
    public void shouldIgnoreUnknownMessageType() {
        final Map<?,?> message = new HashMap<>();

        assertReportError(message);

        queryProbe.assertWasNotSubscribed();
        commandProbe.assertWasNotSubscribed();
        eventProbe.assertWasNotSubscribed();
    }

    private void assertReportError(Object message){
        customReporter.reportError(new RuntimeException(), rawMessage, message, true)
            .as(StepVerifier::create)
            .verifyComplete();
    }

    class TestCustomReporter implements CustomErrorReporter {
        @Override
        public Mono<Void> reportError(Throwable ex, Message rawMessage, Command<?> message, boolean redelivered) {
            return commandProbe.mono();
        }

        @Override
        public Mono<Void> reportError(Throwable ex, Message rawMessage, DomainEvent<?> message, boolean redelivered) {
            return eventProbe.mono();
        }

        @Override
        public Mono<Void> reportError(Throwable ex, Message rawMessage, AsyncQuery<?> message, boolean redelivered) {
            return queryProbe.mono();
        }
    }

}

