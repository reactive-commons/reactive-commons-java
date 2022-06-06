package org.reactivecommons.async.impl.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.impl.SNSDirectAsyncGateway;
import org.reactivecommons.async.impl.config.props.BrokerConfigProps;
import org.reactivecommons.async.impl.reply.ReactiveReplyRouter;
import org.reactivecommons.async.impl.sns.communications.Sender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(AWSConfig.class)
@RequiredArgsConstructor
public class DirectAsyncGatewayConfig {

    private final BrokerConfigProps props;

    @Bean
    public SNSDirectAsyncGateway sqsDirectAsyncGateway( Sender rSender ) throws Exception {
        return new SNSDirectAsyncGateway(rSender, props.getDirectMessagesExchangeName());
    }

    @Bean
    public BrokerConfig brokerConfig() {
        return new BrokerConfig();
    }


    @Bean
    public ReactiveReplyRouter router() {
        return new ReactiveReplyRouter();
    }


}
