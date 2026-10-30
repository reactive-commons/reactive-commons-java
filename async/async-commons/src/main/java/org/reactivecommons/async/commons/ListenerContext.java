package org.reactivecommons.async.commons;

import lombok.With;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;

/**
 * What the listeners of a domain share: where they consume the messages from, how they handle them, retry them and
 * report the failures. Each listener receives it with the settings of its own queue or topic.
 *
 * @param listener       consumes the messages from the broker
 * @param resolver       the handlers of the messages
 * @param converter      reads the messages for their handlers
 * @param retryPolicy    how a message whose handler failed is retried
 * @param createTopology whether the listener declares the queues, exchanges and bindings it consumes from
 * @param <L>            the type of the listener of the broker
 */
@With
public record ListenerContext<L>(L listener, HandlerResolver resolver, MessageConverter converter,
                                 RetryPolicy retryPolicy, boolean createTopology, DiscardNotifier discardNotifier,
                                 CustomReporter errorReporter) {
}
