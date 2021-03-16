package org.reactivecommons.async.parent;

import org.reactivecommons.async.parent.communications.Message;
import reactor.core.publisher.Mono;

public interface DiscardNotifier {

    Mono<Void> notifyDiscard(Message message);

}
