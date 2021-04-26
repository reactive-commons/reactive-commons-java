package org.reactivecommons.async.rabbit.listeners;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.rabbit.HandlerResolver;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

import static reactor.core.publisher.Mono.error;

@ExtendWith(MockitoExtension.class)
public class ApplicationEventListenerTest extends ListenerReporterTestSuperClass {

    private DomainEvent<DummyMessage> event1 = new DomainEvent<>("app.event.test", UUID.randomUUID().toString(), new DummyMessage());
    private DomainEvent<DummyMessage> event2 = new DomainEvent<>("app.event.test2", UUID.randomUUID().toString(), new DummyMessage());

    @Test
    public void shouldSendErrorToCustomErrorReporter() throws InterruptedException {
        final HandlerRegistry registry = HandlerRegistry.register()
                .listenEvent("app.event.test", m -> error(new RuntimeException("testEx")), DummyMessage.class);
        assertSendErrorToCustomReporter(registry, createSource(DomainEvent::getName, event1));
    }

    @Test
    public void shouldContinueAfterReportError() throws InterruptedException {
        final HandlerRegistry handlerRegistry = HandlerRegistry.register()
                .listenEvent("app.event.test", m -> error(new RuntimeException("testEx")), DummyMessage.class)
                .listenEvent("app.event.test2", m -> Mono.fromRunnable(successSemaphore::release), DummyMessage.class);

        assertContinueAfterSendErrorToCustomReporter(handlerRegistry, createSource(DomainEvent::getName, event1, event2));
    }

    @Override
    protected GenericMessageListener createMessageListener(HandlerResolver handlerResolver) {
        return new StubGenericMessageListener(handlerResolver);
    }

    class StubGenericMessageListener extends ApplicationEventListener {
        public StubGenericMessageListener(HandlerResolver handlerResolver) {
            super(reactiveMessageListener, "queueName", handlerResolver, "", messageConverter, true, 10, 10, Optional.empty(), discardNotifier, errorReporter, "");
        }
    }
}
