package org.reactivecommons.async.commons;

/**
 * How a listener retries a message whose handler failed.
 *
 * @param withDLQRetry whether the message is retried through a dead letter queue, which delays it by
 *                     {@code retryDelay}, instead of by the listener itself
 * @param maxRetries   how many times a message is retried before it is discarded, -1 for the default of the strategy
 * @param retryDelay   the delay before a message is retried, in milliseconds
 */
public record RetryPolicy(boolean withDLQRetry, long maxRetries, int retryDelay) {
}
