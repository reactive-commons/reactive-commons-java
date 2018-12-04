package org.reactivecommons.async.impl.config;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.reactivecommons.async.impl.RabbitDirectAsyncGateway;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.listeners.ApplicationReplyListener;
import org.reactivecommons.async.impl.reply.ReactiveReplyRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.util.Base64Utils;

import java.nio.ByteBuffer;
import java.util.UUID;

@Configuration
@Import(RabbitMqConfig.class)
@AllArgsConstructor
@NoArgsConstructor
public class DirectAsyncGatewayConfig {

    @Value("${app.async.direct.exchange:directMessages}")
    private String directMessagesExchangeName;

    @Value("${spring.application.name}")
    private String appName;

    @Bean
    public RabbitDirectAsyncGateway rabbitDirectAsyncGateway(BrokerConfig config, ReactiveReplyRouter router, ReactiveMessageSender rSender) throws Exception {
        return new RabbitDirectAsyncGateway(config, router, rSender, directMessagesExchangeName);
    }

    @Bean
    public ApplicationReplyListener msgListener(ReactiveReplyRouter router, BrokerConfig config, ReactiveMessageListener listener)  {
        final ApplicationReplyListener replyListener = new ApplicationReplyListener(router, listener, generateName());
        replyListener.startListening(config.getRoutingKey());
        return replyListener;
    }


    @Bean
    public BrokerConfig brokerConfig() {
        return new BrokerConfig();
    }


    @Bean
    public ReactiveReplyRouter router() {
        return new ReactiveReplyRouter();
    }

    public String generateName() {
        UUID uuid = UUID.randomUUID();
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits())
            .putLong(uuid.getLeastSignificantBits());
        // Convert to base64 and remove trailing =
        return this.appName + Base64Utils.encodeToUrlSafeString(bb.array())
            .replaceAll("=", "");
    }
}
