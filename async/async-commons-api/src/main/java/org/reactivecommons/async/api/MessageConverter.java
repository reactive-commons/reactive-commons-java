package org.reactivecommons.async.api;

import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;

public interface MessageConverter {

    <T> AsyncQuery<T> readAsyncQuery(Message message, Class<T> bodyClass);

    <T> DomainEvent<T> readDomainEvent(Message message, Class<T> bodyClass);

    <T> Command<T> readCommand(Message message, Class<T> bodyClass);

    Message toMessage(Object object);

}
