package org.reactivecommons.async.rabbit.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.AllArgsConstructor;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.RabbitDirectAsyncGateway;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.listeners.ApplicationReplyListener;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

@AllArgsConstructor
public class DirectAsyncGatewayConfig {

    private String directMessagesExchangeName;
    private String globalReplyExchangeName;
    private String appName;


    public RabbitDirectAsyncGateway rabbitDirectAsyncGateway(BrokerConfig config, ReactiveReplyRouter router, ReactiveMessageSender rSender, MessageConverter converter,
                                                             MeterRegistry meterRegistry) throws Exception {
        return new RabbitDirectAsyncGateway(config, router, rSender, directMessagesExchangeName, converter, meterRegistry);
    }

    public ApplicationReplyListener msgListener(ReactiveReplyRouter router, BrokerConfig config, ReactiveMessageListener listener, boolean createTopology) {
        final ApplicationReplyListener replyListener = new ApplicationReplyListener(router, listener, generateName(), globalReplyExchangeName, createTopology);
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
