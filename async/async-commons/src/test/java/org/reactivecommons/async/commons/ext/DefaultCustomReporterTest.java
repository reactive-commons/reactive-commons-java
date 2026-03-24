package org.reactivecommons.async.commons.ext;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.commons.communications.Message;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatNoException;
class DefaultCustomReporterTest {

    DefaultCustomReporter reporter = new DefaultCustomReporter();
    Message msg = createMessage();

    @Test
    void reportErrorCommandReturnsEmpty() {
        reporter.reportError(new RuntimeException(), msg, new Command<>("cmd", "id", "data"), false)
                .as(StepVerifier::create)
                .verifyComplete();
    }

    @Test
    void reportErrorDomainEventReturnsEmpty() {
        reporter.reportError(new RuntimeException(), msg, new DomainEvent<>("ev", "id", "data"), false)
                .as(StepVerifier::create)
                .verifyComplete();
    }

    @Test
    void reportErrorAsyncQueryReturnsEmpty() {
        reporter.reportError(new RuntimeException(), msg, new AsyncQuery<>("res", "data"), false)
                .as(StepVerifier::create)
                .verifyComplete();
    }

    @Test
    void reportErrorRoutesCommand() {
        var command = new Command<>("cmd", "id", "data");
        reporter.reportError(new RuntimeException(), msg, (Object) command, false)
                .as(StepVerifier::create)
                .verifyComplete();
    }

    @Test
    void reportErrorRoutesDomainEvent() {
        var event = new DomainEvent<>("ev", "id", "data");
        reporter.reportError(new RuntimeException(), msg, (Object) event, false)
                .as(StepVerifier::create)
                .verifyComplete();
    }

    @Test
    void reportErrorRoutesAsyncQuery() {
        var query = new AsyncQuery<>("res", "data");
        reporter.reportError(new RuntimeException(), msg, (Object) query, false)
                .as(StepVerifier::create)
                .verifyComplete();
    }

    @Test
    void reportErrorUnknownTypeReturnsEmpty() {
        var result = reporter.reportError(new RuntimeException(), msg, "unknown", false);
        assertThatNoException().isThrownBy(() -> result.as(StepVerifier::create).verifyComplete());
    }

    @Test
    void reportMetricDoesNothing() {
        assertThatNoException().isThrownBy(() -> reporter.reportMetric("type", "path", 100L, true));
    }

    private Message createMessage() {
        return new Message() {
            @Override
            public String getType() { return "test"; }
            @Override
            public byte[] getBody() { return new byte[0]; }
            @Override
            public Properties getProperties() {
                return new Properties() {
                    @Override
                    public String getContentType() { return "application/json"; }
                    @Override
                    public long getContentLength() { return 0; }
                    @Override
                    public Map<String, Object> getHeaders() { return Map.of(); }
                };
            }
        };
    }
}
