package org.reactivecommons.async.rabbit.config.props;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;

import java.util.concurrent.atomic.AtomicReference;

import static org.reactivecommons.async.commons.utils.NameGenerator.fromNameWithSuffix;
import static org.reactivecommons.async.commons.utils.NameGenerator.generateNameFrom;


@Getter
@RequiredArgsConstructor
public class BrokerConfigProps implements IBrokerConfigProps {
    private final AsyncProps asyncProps;
    private final AtomicReference<String> replyQueueName = new AtomicReference<>();
    private final AtomicReference<String> notificationsQueueName = new AtomicReference<>();

    @Override
    public String getEventsQueue() {
        return fromNameWithSuffix(getAppName(), asyncProps.getDomain().getEvents().getEventsSuffix());
    }

    @Override
    public String getNotificationsQueue() {
        return resolveTemporaryQueue(notificationsQueueName, asyncProps.getDomain().getEvents().getNotificationSuffix());
    }

    @Override
    public String getQueriesQueue() {
        return fromNameWithSuffix(getAppName(), asyncProps.getDirect().getQuerySuffix());
    }

    @Override
    public String getCommandsQueue() {
        return fromNameWithSuffix(getAppName(), asyncProps.getDirect().getCommandSuffix());
    }

    @Override
    public String getReplyQueue() {
        return resolveTemporaryQueue(replyQueueName, asyncProps.getGlobal().getRepliesSuffix());
    }

    @Override
    public String getDomainEventsExchangeName() {
        return asyncProps.getDomain().getEvents().getExchange();
    }

    @Override
    public String getDirectMessagesExchangeName() {
        return asyncProps.getDirect().getExchange();
    }

    @Override
    public String getGlobalReplyExchangeName() {
        return asyncProps.getGlobal().getExchange();
    }

    @Override
    public String getAppName() {
        return asyncProps.getAppName();
    }

    private String resolveTemporaryQueue(AtomicReference<String> property, String suffix) {
        final String name = property.get();
        if (name == null) {
            final String replyName = generateNameFrom(getAppName(), suffix);
            if (property.compareAndSet(null, replyName)) {
                return replyName;
            } else {
                return property.get();
            }
        }
        return name;
    }
}
