package reactor.rabbitmq;

import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static java.time.Duration.ofMillis;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExceptionHandlersTest {

    @Test
    void connectionRecoveryTriggeringHardErrorNotByApplication() {
        Predicate<Throwable> predicate = new ExceptionHandlers.ConnectionRecoveryTriggeringPredicate();
        assertTrue(predicate.test(new ShutdownSignalException(true, false, null, null)),
                "hard error, not triggered by application");
    }

    @Test
    void connectionRecoveryTriggeringSoftErrorNotByApplication() {
        Predicate<Throwable> predicate = new ExceptionHandlers.ConnectionRecoveryTriggeringPredicate();
        assertTrue(predicate.test(new ShutdownSignalException(false, false, null, null)),
                "soft error, not triggered by application");
    }

    @Test
    void connectionRecoveryNotTriggeringWhenInitiatedByApplication() {
        Predicate<Throwable> predicate = new ExceptionHandlers.ConnectionRecoveryTriggeringPredicate();
        assertFalse(predicate.test(new ShutdownSignalException(false, true, null, null)),
                "soft error, triggered by application");
    }

    @Test
    void connectionRecoveryFalseForNonShutdownSignal() {
        Predicate<Throwable> predicate = new ExceptionHandlers.ConnectionRecoveryTriggeringPredicate();
        assertFalse(predicate.test(new RuntimeException("not a shutdown signal")));
    }

    @Test
    void connectionRecoveryPredicateConstantShouldWork() {
        assertNotNull(ExceptionHandlers.CONNECTION_RECOVERY_PREDICATE);
        assertFalse(ExceptionHandlers.CONNECTION_RECOVERY_PREDICATE.test(new RuntimeException()));
    }

    @Test
    void simpleRetryTemplateShouldThrowIfNotRetryable() {
        var retryTemplate = new ExceptionHandlers.SimpleRetryTemplate(
                ofMillis(100), ofMillis(10), ExceptionHandlers.CONNECTION_RECOVERY_PREDICATE);
        var illegalArgEx = new IllegalArgumentException();
        assertThatThrownBy(() -> retryTemplate.retry(() -> null, illegalArgEx))
                .isInstanceOf(RabbitFluxException.class);
    }

    @Test
    void simpleRetryTemplateInvalidArguments() {
        var timeout100 = ofMillis(100);
        var timeout10 = ofMillis(10);
        assertThatThrownBy(() -> new ExceptionHandlers.SimpleRetryTemplate(null, timeout10, e -> true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExceptionHandlers.SimpleRetryTemplate(timeout100, null, e -> true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExceptionHandlers.SimpleRetryTemplate(timeout10, timeout100, e -> true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExceptionHandlers.SimpleRetryTemplate(timeout100, timeout10, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void retrySucceeds() {
        var handler = new ExceptionHandlers.RetrySendingExceptionHandler(
                ofMillis(200), ofMillis(10), e -> true);
        AtomicLong counter = new AtomicLong(0);
        handler.accept(sendContext(() -> {
            if (counter.incrementAndGet() < 3) {
                throw new Exception();
            }
            return null;
        }), new Exception());
        assertEquals(3, counter.get());
    }

    @Test
    void retryTimeoutIsReachedAndDoesNotThrowWhenNotConfigured() {
        var handler = new ExceptionHandlers.RetrySendingExceptionHandler(
                ofMillis(50), ofMillis(10), e -> true, false);
        var ctx = sendContext(() -> { throw new Exception(); });
        assertThatNoException().isThrownBy(() -> handler.accept(ctx, new Exception()));
    }

    @Test
    void retryTimeoutThrowsWhenConfigured() {
        var handler = new ExceptionHandlers.RetrySendingExceptionHandler(
                ofMillis(50), ofMillis(10), e -> true, true);
        var ctx = sendContext(() -> { throw new Exception(); });
        var triggerEx = new Exception();
        assertThatThrownBy(() -> handler.accept(ctx, triggerEx))
                .isInstanceOf(RabbitFluxRetryTimeoutException.class);
    }

    @Test
    void retryAcknowledgmentHandlerCallsAckOrNack() {
        var handler = new ExceptionHandlers.RetryAcknowledgmentExceptionHandler(
                ofMillis(200), ofMillis(10), e -> true);
        AtomicLong counter = new AtomicLong(0);
        var context = new Receiver.AcknowledgmentContext(null, delivery -> {
            if (counter.incrementAndGet() < 2) {
                throw new RuntimeException("transient");
            }
        });
        handler.accept(context, new RuntimeException("initial"));
        assertEquals(2, counter.get());
    }

    private Sender.SendContext<OutboundMessage> sendContext(Callable<Void> callable) {
        return new Sender.SendContext<>(null, null) {
            @Override
            public void publish() throws Exception {
                callable.call();
            }
        };
    }
}
