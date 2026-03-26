package org.reactivecommons.async.commons;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.From;
import org.reactivecommons.async.api.handlers.QueryHandlerDelegate;
import org.reactivecommons.async.commons.communications.Message;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryExecutorTest {

    @SuppressWarnings("unchecked")
    @Test
    void executeCallsHandlerWithConvertedMessage() {
        QueryHandlerDelegate<String, String> handler = mock(QueryHandlerDelegate.class);
        when(handler.handle(any(From.class), any())).thenReturn(Mono.just("result"));

        QueryExecutor<String, String> executor = new QueryExecutor<>(handler, msg -> "query");
        StepVerifier.create(executor.execute(createMessage()))
                .expectNext("result")
                .verifyComplete();
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
                    public Map<String, Object> getHeaders() {
                        return Map.of("correlationID", "corr-123", "replyID", "reply-456");
                    }
                };
            }
        };
    }
}
