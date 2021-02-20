package org.reactivecommons.async.impl.listeners;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static reactor.core.publisher.Mono.error;

@ExtendWith(MockitoExtension.class)
public class ApplicationQueryListenerErrorTest extends ListenerReporterTestSuperClass {

    private AsyncQuery<DummyMessage> event1 = new AsyncQuery<>("app.query.test", new DummyMessage());
    private AsyncQuery<DummyMessage> event2 = new AsyncQuery<>("app.query.test2", new DummyMessage());

    @Test
    public void shouldSendErrorToCustomErrorReporter() throws InterruptedException {
        final HandlerRegistry registry = HandlerRegistry.register()
                .serveQuery("app.query.test", m -> error(new RuntimeException("testEx")), DummyMessage.class);
        assertSendErrorToCustomReporter(registry, createSource(AsyncQuery::getResource, event1));
    }

    @Test
    public void shouldContinueAfterReportError() throws InterruptedException {
        final HandlerRegistry handlerRegistry = HandlerRegistry.register()
                .serveQuery("app.query.test", m -> error(new RuntimeException("testEx")), DummyMessage.class)
                .serveQuery("app.query.test2", m -> Mono.fromRunnable(successSemaphore::release), DummyMessage.class);

        assertContinueAfterSendErrorToCustomReporter(handlerRegistry, createSource(AsyncQuery::getResource, event1, event2));
    }

    @Override
    protected GenericMessageListener createMessageListener(HandlerResolver handlerResolver) {
        return new StubGenericMessageListener(handlerResolver);
    }

    class StubGenericMessageListener extends ApplicationQueryListener {

        public StubGenericMessageListener(HandlerResolver handlerResolver) {
            super(reactiveMessageListener, "queueName", handlerResolver, mock(ReactiveMessageSender.class), "exchange", messageConverter, "exchange", true, 10L, 100, Optional.of(1), discardNotifier, errorReporter);
        }

    }
}
