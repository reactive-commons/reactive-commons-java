package org.reactivecommons.async.commons;

import org.reactivecommons.async.commons.communications.Message;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface DiscardNotifier {
    Mono<Void> notifyDiscard(Message message);

    /**
     * Like {@link #notifyDiscard(Message)}, but fails when the notification could not be published and publishing it
     * again may succeed, so that the caller can keep the discarded message (and discard it again later) instead of
     * acknowledging (and losing) it. A notification that was published but not routed, or that can never be
     * published, completes: keeping the message could not help. Notifiers that cannot tell whether the notification
     * was published complete as {@link #notifyDiscard(Message)} does.
     *
     * @param message the discarded message
     * @return completes once the discard is notified (or can never be), fails if it could not be yet
     */
    default Mono<Void> tryNotifyDiscard(Message message) {
        return notifyDiscard(message);
    }
}
