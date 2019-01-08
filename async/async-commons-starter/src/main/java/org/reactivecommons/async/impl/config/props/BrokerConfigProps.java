package org.reactivecommons.async.impl.config.props;

import lombok.Getter;
import org.reactivecommons.async.impl.config.IBrokerConfigProps;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Base64Utils;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;


@Getter
@Configuration
public class BrokerConfigProps implements IBrokerConfigProps {

    @Value("${spring.application.name}")
    private String appName;

    @Value("${app.async.domain.events.exchange:domainEvents}")
    private String domainEventsExchangeName;

    @Value("${app.async.direct.exchange:directMessages}")
    private String directMessagesExchangeName;

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
            final String replyName =  newRandomQueueName();
            if (replyQueueName.compareAndSet(null, replyName)) {
                return replyName;
            } else {
                return replyQueueName.get();
            }
        }
        return name;
    }

    private String newRandomQueueName() {
        UUID uuid = UUID.randomUUID();
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits())
            .putLong(uuid.getLeastSignificantBits());
        return appName + Base64Utils.encodeToUrlSafeString(bb.array())
            .replaceAll("=", "");
    }

}
