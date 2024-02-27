package org.reactivecommons.async.rabbit.config.props;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.commons.utils.NameGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicReference;


@Getter
@Configuration
@RequiredArgsConstructor
public class BrokerConfigProps implements IBrokerConfigProps {

    @Value("${spring.application.name}")
    private String appName;
    private final AsyncProps asyncProps;
    private final AtomicReference<String> replyQueueName = new AtomicReference<>();

    @Override
    public String getEventsQueue() {
        return appName + ".subsEvents";
    }

    @Override
    public String getQueriesQueue() {
        return appName + ".query";
    }

    @Override
    public String getCommandsQueue() {
        return appName;
    }

    @Override
    public String getReplyQueue() {
        final String name = replyQueueName.get();
        if (name == null) {
            final String replyName = NameGenerator.generateNameFrom(appName, "reply");
            if (replyQueueName.compareAndSet(null, replyName)) {
                return replyName;
            } else {
                return replyQueueName.get();
            }
        }
        return name;
    }

    @Override
    public String getDomainEventsExchangeName() {
        return asyncProps.getDomain().getEvents().getExchange();
    }

    @Override
    public String getDirectMessagesExchangeName() {
        return asyncProps.getDirect().getExchange();
    }
}
