package org.reactivecommons.async.commons.config;

public interface IBrokerConfigProps {
    String getEventsQueue();

    String getQueriesQueue();

    String getCommandsQueue();

    String getReplyQueue();

    String getAppName();

    String getDomainEventsExchangeName();

    String getDirectMessagesExchangeName();

    java.util.concurrent.atomic.AtomicReference<String> getReplyQueueName();
}
