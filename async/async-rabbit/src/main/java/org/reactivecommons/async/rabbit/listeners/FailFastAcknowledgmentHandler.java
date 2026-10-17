package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.ShutdownSignalException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.java.Log;
import reactor.rabbitmq.RabbitFluxException;
import reactor.rabbitmq.Receiver;

import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Handles a failed ack/nack without blocking the thread.
 * <p>
 * Acks run on the thread that completed the handler, which can be a Netty event loop shared with the HTTP server,
 * so the default handler of reactor-rabbitmq, which retries for up to 10 seconds with {@code Thread.sleep}, must not
 * be used. When the channel or the connection is closed there is nothing to retry: the broker requeues every
 * unacknowledged delivery of a closed channel, and after a connection recovery the client ignores acks with stale
 * delivery tags anyway. Any other error is reported to the caller.
 *
 * @see <a href="https://www.rabbitmq.com/docs/confirms#automatic-requeueing">Automatic requeueing</a>
 * @see <a href="https://www.rabbitmq.com/client-libraries/java-api-guide#recovery-and-acknowledgements">Manual
 * Acknowledgements and Automatic Recovery</a>
 */
@Log
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class FailFastAcknowledgmentHandler implements BiConsumer<Receiver.AcknowledgmentContext, Exception> {

    static final FailFastAcknowledgmentHandler INSTANCE = new FailFastAcknowledgmentHandler();

    @Override
    public void accept(Receiver.AcknowledgmentContext context, Exception error) {
        if (isChannelClosed(error)) {
            log.log(Level.WARNING, () -> "Acknowledgment skipped, the channel is closed (the broker redelivers "
                    + "the message): " + error.getMessage());
            return;
        }
        throw new RabbitFluxException("Unable to acknowledge the message", error);
    }

    private static boolean isChannelClosed(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ShutdownSignalException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
