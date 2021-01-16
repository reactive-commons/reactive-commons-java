package org.reactivecommons.async.impl.listeners;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static reactor.core.publisher.Mono.empty;
import static reactor.core.publisher.Mono.error;

@RunWith(MockitoJUnitRunner.class)
public class ApplicationNotificationListenerTest extends ListenerReporterTestSuperClass{

    private DomainEvent<DummyMessage> event1 = new DomainEvent<>("app.event.test", UUID.randomUUID().toString(), new DummyMessage());
    private DomainEvent<DummyMessage> event2 = new DomainEvent<>("app.event.test2", UUID.randomUUID().toString(), new DummyMessage());

    @Test
    public void shouldSendErrorToCustomErrorReporter() throws InterruptedException {
        final HandlerRegistry registry = HandlerRegistry.register()
            .listenNotificationEvent("app.event.test", m -> error(new RuntimeException("testEx")), DummyMessage.class);
        assertSendErrorToCustomReporter(registry, createSource(DomainEvent::getName, event1));
    }

    @Test
    public void shouldContinueAfterReportError() throws InterruptedException {
        final HandlerRegistry handlerRegistry = HandlerRegistry.register()
            .listenNotificationEvent("app.event.test", m -> error(new RuntimeException("testEx")), DummyMessage.class)
            .listenNotificationEvent("app.event.test2", m -> Mono.fromRunnable(successSemaphore::release), DummyMessage.class);

        assertContinueAfterSendErrorToCustomReporter(handlerRegistry, createSource(DomainEvent::getName, event1, event2));
    }

    @Override
    protected GenericMessageListener createMessageListener(HandlerResolver handlerResolver) {
        return new StubGenericMessageListener(handlerResolver);
    }

    class StubGenericMessageListener extends ApplicationNotificationListener {

        public StubGenericMessageListener(HandlerResolver handlerResolver) {
            super(reactiveMessageListener, "exchange", "queue", handlerResolver, messageConverter, discardNotifier, errorReporter);
        }

        @Override
        protected Mono<Void> setUpBindings(TopologyCreator creator) {
            return empty(); //Do Nothing
        }

    }
}
