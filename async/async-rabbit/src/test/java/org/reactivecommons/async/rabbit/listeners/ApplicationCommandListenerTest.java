package org.reactivecommons.async.rabbit.listeners;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.rabbit.HandlerResolver;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.verify;
import static reactor.core.publisher.Mono.*;

@ExtendWith(MockitoExtension.class)
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
    public void shouldSendErrorMetricToCustomErrorReporter() throws InterruptedException {
        final HandlerRegistry registry = HandlerRegistry.register()
            .handleCommand("app.command.test", m -> error(new RuntimeException("testEx")), DummyMessage.class);
        assertSendErrorToCustomReporter(registry, createSource(Command::getName, command));
        verify(errorReporter).reportMetric(eq("command"), eq("app.command.test"), longThat(time -> time > 0 ), eq(false));
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
    }
}
