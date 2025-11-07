package org.reactivecommons.async.rabbit.listeners;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueueListener;
import org.reactivecommons.async.commons.HandlerResolver;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.verify;
import static reactor.core.publisher.Mono.error;

@ExtendWith(MockitoExtension.class)
class ApplicationQueueListenerTest extends ListenerReporterTestSuperClass {

    private final TestRawMessage message = new TestRawMessage("test.queue");
    private final TestRawMessage message2 = new TestRawMessage("test.queue");

    @Test
    void shouldSendErrorToCustomErrorReporter() throws InterruptedException {
        final HandlerRegistry registry = HandlerRegistry.register()
                .listenQueue("test.queue", m -> error(new RuntimeException("testEx")));
        assertSendErrorToCustomReporter(registry, createSource(m -> "test.queue", message));
    }

    @Test
    void shouldSendErrorMetricToCustomErrorReporter() throws InterruptedException {
        final HandlerRegistry registry = HandlerRegistry.register()
                .listenQueue("test.queue", m -> error(new RuntimeException("testEx")));
        assertSendErrorToCustomReporter(registry, createSource(m -> "test.queue", message));
        verify(errorReporter)
                .reportMetric(
                        eq("queue"), eq("test.queue"), longThat(time -> time >= 0), eq(false)
                );
    }

    @Test
    void shouldContinueAfterReportError() throws InterruptedException {
        final boolean[] firstMessage = {true};
        final HandlerRegistry registry = HandlerRegistry.register()
                .listenQueue("test.queue", m -> {
                    if (firstMessage[0]) {
                        firstMessage[0] = false;
                        return error(new RuntimeException("testEx"));
                    }
                    return Mono.fromRunnable(successSemaphore::release);
                });

        assertContinueAfterSendErrorToCustomReporter(
                registry, createSource(m -> "test.queue", message, message2)
        );
    }

    @Test
    void shouldListenQueueWithTopologySetup() throws InterruptedException {
        final HandlerRegistry registry = HandlerRegistry.register()
                .listenQueue("test.queue",
                        m -> error(new RuntimeException("testEx")),
                        creator -> Mono.empty());

        assertSendErrorToCustomReporter(registry, createSource(m -> "test.queue", message));
    }

    @Test
    void shouldListenQueueWithCustomDomain() {
        final HandlerRegistry registry = HandlerRegistry.register()
                .listenQueue("customDomain", "test.queue", m -> Mono.empty());

        assertThat(registry.getQueueHandlers().get("customDomain"))
                .hasSize(1)
                .first()
                .extracting(RegisteredQueueListener::queueName)
                .isEqualTo("test.queue");
    }

    @Test
    void shouldListenQueueWithCustomDomainAndTopologySetup() {
        final HandlerRegistry registry = HandlerRegistry.register()
                .listenQueue("customDomain", "test.queue",
                        m -> Mono.empty(),
                        creator -> Mono.empty());

        assertThat(registry.getQueueHandlers().get("customDomain"))
                .hasSize(1)
                .first()
                .extracting(RegisteredQueueListener::queueName)
                .isEqualTo("test.queue");
    }

    @Test
    void shouldRegisterMultipleQueuesInDifferentDomains() {
        final HandlerRegistry registry = HandlerRegistry.register()
                .listenQueue("domain1", "test.queue1", m -> Mono.empty())
                .listenQueue("domain2", "test.queue2", m -> Mono.empty())
                .listenQueue("test.queue3", m -> Mono.empty());

        assertThat(registry.getQueueHandlers().get("domain1")).hasSize(1);
        assertThat(registry.getQueueHandlers().get("domain2")).hasSize(1);
        assertThat(registry.getQueueHandlers().get(HandlerRegistry.DEFAULT_DOMAIN)).hasSize(1);
    }

    @Test
    void shouldRegisterMultipleQueuesInSameDomain() {
        final HandlerRegistry registry = HandlerRegistry.register()
                .listenQueue("customDomain", "test.queue1", m -> Mono.empty())
                .listenQueue("customDomain", "test.queue2", m -> Mono.empty(), creator -> Mono.empty())
                .listenQueue("customDomain", "test.queue3", m -> Mono.empty());

        assertThat(registry.getQueueHandlers().get("customDomain"))
                .hasSize(3)
                .extracting(RegisteredQueueListener::queueName)
                .containsExactlyInAnyOrder("test.queue1", "test.queue2", "test.queue3");
    }

    @Override
    protected GenericMessageListener createMessageListener(HandlerResolver handlerResolver) {
        RegisteredQueueListener registeredListener = handlerResolver.getQueueListeners().get("test.queue");
        return new ApplicationQueueListener(
                reactiveMessageListener,
                true,
                10L,
                10,
                registeredListener,
                discardNotifier,
                errorReporter
        );
    }

    record TestRawMessage(String queueName) {
    }
}
