package org.reactivecommons.async.impl.listeners;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

import static reactor.core.publisher.Mono.empty;
import static reactor.core.publisher.Mono.error;

@RunWith(MockitoJUnitRunner.class)
public class ApplicationCommandListenerTest extends ListenerReporterTestSuperClass{

    private final Command<DummyMessage> command = new Command<>("app.command.test", UUID.randomUUID().toString(), new DummyMessage());
    private final Command<DummyMessage> command2 = new Command<>("app.command.test2", UUID.randomUUID().toString(), new DummyMessage());

    @Test
    public void shouldSendErrorToCustomErrorReporter() throws InterruptedException {
        final HandlerRegistry registry = HandlerRegistry.register()
            .handleCommand("app.command.test", m -> error(new RuntimeException("testEx")), DummyMessage.class);
        assertSendErrorToCustomReporter(registry, createSource(Command::getName, command));
    }

    @Test
    public void shouldContinueAfterReportError() throws InterruptedException {
        final HandlerRegistry handlerRegistry = HandlerRegistry.register()
            .handleCommand("app.command.test", m -> error(new RuntimeException("testEx")), DummyMessage.class)
            .handleCommand("app.command.test2", m -> Mono.fromRunnable(successSemaphore::release), DummyMessage.class);

        assertContinueAfterSendErrorToCustomReporter(handlerRegistry, createSource(Command::getName, command, command2));
    }

    @Override
    protected GenericMessageListener createMessageListener(HandlerResolver handlerResolver) {
        return new StubGenericMessageListener(handlerResolver);
    }

    class StubGenericMessageListener extends ApplicationCommandListener {

        public StubGenericMessageListener(HandlerResolver handlerResolver) {
            super(reactiveMessageListener, "queueName", handlerResolver, "directExchange", messageConverter, true,  10, 10, Optional.empty(), discardNotifier, errorReporter);
        }

        @Override
        protected Mono<Void> setUpBindings(TopologyCreator creator) {
            return empty(); //Do Nothing
        }

    }
}
