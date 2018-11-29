package org.reactivecommons.async.impl.converters;

import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.impl.communications.Message;

public interface MessageConverter {

    <T> AsyncQuery<T> readAsyncQuery(Message message, Class<T> bodyClass);

    <T> DomainEvent<T> readDomainEvent(Message message, Class<T> bodyClass);

    <T> Command<T> readCommand(Message message, Class<T> bodyClass);

    Message toMessage(Object object);

}
