package org.reactivecommons.async.impl.config;

import org.reactivecommons.async.impl.RabbitDirectAsyncGateway;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.listeners.ApplicationReplyListener;
import org.reactivecommons.async.impl.reply.ReactiveReplyRouter;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

public class DirectAsyncGatewayConfig {

    private String directMessagesExchangeName;
    private String appName;

    public DirectAsyncGatewayConfig(String directMessagesExchangeName, String appName) {
        this.directMessagesExchangeName = directMessagesExchangeName;
        this.appName = appName;
    }

    public RabbitDirectAsyncGateway rabbitDirectAsyncGateway(BrokerConfig config, ReactiveReplyRouter router, ReactiveMessageSender rSender) throws Exception {
        return new RabbitDirectAsyncGateway(config, router, rSender, directMessagesExchangeName);
    }

    public ApplicationReplyListener msgListener(ReactiveReplyRouter router, BrokerConfig config, ReactiveMessageListener listener)  {
        final ApplicationReplyListener replyListener = new ApplicationReplyListener(router, listener, generateName());
        replyListener.startListening(config.getRoutingKey());
        return replyListener;
    }


    public BrokerConfig brokerConfig() {
        return new BrokerConfig();
    }


    public ReactiveReplyRouter router() {
        return new ReactiveReplyRouter();
    }

    public String generateName() {
        UUID uuid = UUID.randomUUID();
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits())
            .putLong(uuid.getLeastSignificantBits());
        // Convert to base64 and remove trailing =
        return this.appName + encodeToUrlSafeString(bb.array())
            .replaceAll("=", "");
    }

    public static String encodeToUrlSafeString(byte[] src) {
        return new String(encodeUrlSafe(src));
    }

    public static byte[] encodeUrlSafe(byte[] src) {
        if (src.length == 0) {
            return src;
        }
        return Base64.getUrlEncoder().encode(src);
    }
}
