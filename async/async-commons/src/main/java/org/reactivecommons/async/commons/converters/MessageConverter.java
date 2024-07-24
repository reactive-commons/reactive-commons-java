package org.reactivecommons.async.commons.converters;

import io.cloudevents.CloudEvent;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.commons.communications.Message;

public interface MessageConverter {

    <T> AsyncQuery<T> readAsyncQuery(Message message, Class<T> bodyClass);

    <T> DomainEvent<T> readDomainEvent(Message message, Class<T> bodyClass);

    <T> Command<T> readCommand(Message message, Class<T> bodyClass);

    CloudEvent readCloudEvent(Message message);

    <T> T readValue(Message message, Class<T> valueClass);

    <T> Command<T> readCommandStructure(Message message);
    <T> DomainEvent<T> readDomainEventStructure(Message message);
    <T> AsyncQuery<T> readAsyncQueryStructure(Message message);

    Message toMessage(Object object);

}
