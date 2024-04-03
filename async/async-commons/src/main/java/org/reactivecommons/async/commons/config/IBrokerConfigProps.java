package org.reactivecommons.async.commons.config;

public interface IBrokerConfigProps {
    String getEventsQueue();

    String getNotificationsQueue();

    String getQueriesQueue();

    String getCommandsQueue();

    String getReplyQueue();

    String getDomainEventsExchangeName();

    String getDirectMessagesExchangeName();

    String getGlobalReplyExchangeName();

    String getAppName();
}
