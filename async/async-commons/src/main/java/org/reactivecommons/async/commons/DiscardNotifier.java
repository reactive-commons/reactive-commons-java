package org.reactivecommons.async.commons;

import org.reactivecommons.async.commons.communications.Message;
import reactor.core.publisher.Mono;

public interface DiscardNotifier {

    Mono<Void> notifyDiscard(Message message);

}
