package org.reactivecommons.async.commons;

import org.reactivecommons.async.commons.communications.Message;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface DiscardNotifier {
    Mono<Void> notifyDiscard(Message message);

    /**
     * Like {@link #notifyDiscard(Message)}, but fails when the notification could not be published, so that the
     * caller can keep the discarded message instead of acknowledging (and losing) it. Notifiers that cannot tell
     * whether the notification was published complete as {@link #notifyDiscard(Message)} does.
     *
     * @param message the discarded message
     * @return completes once the discard is notified, fails if it could not be
     */
    default Mono<Void> tryNotifyDiscard(Message message) {
        return notifyDiscard(message);
    }
}
