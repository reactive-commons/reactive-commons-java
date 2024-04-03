package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.rabbit.HandlerResolver;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.QueueSpecification;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static reactor.core.publisher.Mono.error;
import static reactor.core.publisher.Mono.just;

@ExtendWith(MockitoExtension.class)
public class ApplicationNotificationListenerTest extends ListenerReporterTestSuperClass {

    private DomainEvent<DummyMessage> event1 = new DomainEvent<>("app.event.test", UUID.randomUUID().toString(), new DummyMessage());
    private DomainEvent<DummyMessage> event2 = new DomainEvent<>("app.event.test2", UUID.randomUUID().toString(), new DummyMessage());

    @BeforeEach
    public void initCreator() {
        Mockito.when(topologyCreator.declare(any(QueueSpecification.class))).thenReturn(just(mock(AMQP.Queue.DeclareOk.class)));
    }

    @Test
    void shouldSendErrorToCustomErrorReporter() throws InterruptedException {
        final HandlerRegistry registry = HandlerRegistry.register()
                .listenNotificationEvent("app.event.test", m -> error(new RuntimeException("testEx")), DummyMessage.class);
        assertSendErrorToCustomReporter(registry, createSource(DomainEvent::getName, event1));
    }

    @Test
    void shouldContinueAfterReportError() throws InterruptedException {
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
            super(reactiveMessageListener, "exchange", "queue", true, handlerResolver, messageConverter, discardNotifier, errorReporter);
        }

    }
}
