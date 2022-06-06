package org.reactivecommons.async.impl.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.impl.config.props.AsyncProps;
import org.reactivecommons.async.impl.config.props.BrokerConfigProps;
import org.reactivecommons.async.impl.handlers.ApplicationCommandHandler;
import org.reactivecommons.async.impl.handlers.ApplicationEventHandler;
import org.reactivecommons.async.impl.sns.communications.Listener;
import org.reactivecommons.async.impl.sns.communications.SQSSender;
import org.reactivecommons.async.impl.sns.communications.Sender;
import org.reactivecommons.async.impl.sns.communications.TopologyCreator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.ListTopicsRequest;
import software.amazon.awssdk.services.sns.model.Topic;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;


@Log
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({
        AWSProperties.class,
        AsyncProps.class
})
@Import({BrokerConfigProps.class, MessageListenersConfig.class})
public class AWSConfig {

    private final AsyncProps asyncProps;

    @Value("${spring.application.name}")
    private String appName;
    private String arnSnsPrefix;
    private String arnSqsPrefix;

    @Bean
    public Sender messageSender(SnsAsyncClient client, AWSProperties awsProperties, BrokerConfigProps props) {
        String exchangeName = props.getDomainEventsExchangeName();
        arnSnsPrefix = getTopicArn(exchangeName, client).block();
        final Sender sender = new Sender(client, appName, arnSnsPrefix);
        arnSqsPrefix = arnSnsPrefix.replace("sns", "sqs");
        return sender;
    }

    @Bean
    public SQSSender sqsSender(SqsAsyncClient client) {
        return new SQSSender(client);
    }

    @Bean("evtListener")
    public Listener messageEventListener(SqsAsyncClient sqsClient, ApplicationEventHandler appEvtListener,
                                         BrokerConfigProps props, TopologyCreator topology, SQSSender sqsSender) {
        final Listener listener = getListener(sqsClient, sqsSender);

        final String exchangeName = props.getDomainEventsExchangeName();

        String queueName = props.getEventsQueue();
        topology.createQueue(queueName).block();
        topology.bind(queueName, exchangeName).block();
        topology.setQueueAttributes(queueName, exchangeName, arnSnsPrefix, arnSqsPrefix).block();
        String queueUrl = topology.getQueueUrl(queueName).block();
        listener.startListener(queueUrl, appEvtListener::handle).subscribe();
        return listener;
    }

    private Listener getListener(SqsAsyncClient sqsClient, SQSSender sqsSender) {
        return Listener.builder()
                .client(sqsClient)
                .sqsSender(sqsSender)
                .retryDelay(asyncProps.getRetryDelay())
                .maxRetries(asyncProps.getMaxRetries())
                .prefetchCount(asyncProps.getPrefetchCount())
                .build();
    }

    @Bean("commandListener")
    public Listener messageCommandListener(SqsAsyncClient sqsClient, ApplicationCommandHandler appCmdListener,
                                           BrokerConfigProps props, TopologyCreator topoloy, SQSSender sqsSender) {
        final Listener listener = getListener(sqsClient, sqsSender);

        final String exchangeName = appName.concat(props.getDirectMessagesExchangeName());

        String queueName = props.getCommandsQueue();
        topoloy.createTopic(exchangeName).block();
        topoloy.createQueue(queueName).block();
        topoloy.bind(queueName, exchangeName).block();
        topoloy.setQueueAttributes(queueName, exchangeName, arnSnsPrefix, arnSqsPrefix).block();
        String queueUrl = topoloy.getQueueUrl(queueName).block();
        listener.startListener(queueUrl, appCmdListener::handle).subscribe();
        return listener;
    }

    @Bean
    public SqsAsyncClient getSQSAsyncClient(AWSProperties awsProperties) {
        Region region = Region.of(awsProperties.getRegion());
        return SqsAsyncClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public SnsAsyncClient getSNSAsyncClient(AWSProperties awsProperties) {
        Region region = Region.of(awsProperties.getRegion());
        return SnsAsyncClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public TopologyCreator getTopology(SqsAsyncClient sqsAsyncClient, SnsAsyncClient snsAsyncClient, BrokerConfigProps props) {
        String queueName = props.getDomainEventsExchangeName();
        return new TopologyCreator(snsAsyncClient, sqsAsyncClient);
    }

    public Flux<Topic> listTopics(SnsAsyncClient snsAsyncClient) {
        return getListTopicRequest()
                .flatMap(request -> Mono.fromFuture(snsAsyncClient.listTopics(request)))
                .flatMapMany((response) -> Flux.fromIterable(response.topics()));
    }

    public Mono<String> getTopicArn(String name, SnsAsyncClient snsAsyncClient) {
        return listTopics(snsAsyncClient)
                .map(Topic::topicArn)
                .filter((topic) -> topic.contains(":" + name))
                .map((e) -> e.replace(":" + name, ""))
                .single();
    }

    private Mono<ListTopicsRequest> getListTopicRequest() {
        return Mono.just(ListTopicsRequest.builder().build());
    }


}
