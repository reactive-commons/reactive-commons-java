package us.sofka.commons.reactive.async;

import com.rabbitmq.client.ConnectionFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.RabbitConnectionFactoryBean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.messaging.Message;
import reactor.core.publisher.Mono;
import reactor.core.publisher.UnicastProcessor;
import reactor.rabbitmq.*;
import reactor.util.concurrent.Queues;
import us.sofka.commons.reactive.async.api.DomainEventBus;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
//@Import(ApplicationCommandListener.class)
public class MessageConfig {

    @Value("${app.async.reply.prefix:}")
    private String prefix;

    @Value("${spring.application.name}")
    private String appName;

    @Value("${app.async.domain.events.exchange:domainEvents}")
    private String domainEventsExchangeName;

    @Value("${app.async.direct.exchange:directMessages}")
    private String directMessagesExchangeName;

    @Bean
    public Queue replyQueue() {
        final String replyPrefix = prefix != null && !prefix.isEmpty() ? prefix : "global-reply-";
        return new AnonymousQueue(new AnonymousQueue.Base64UrlNamingStrategy(replyPrefix));
    }

    @Bean
    public DomainEventBus domainEventBus(RabbitConnectionFactoryBean bean) throws Exception {
        final ReactiveMessageSender rSender =  reactiveMessageSender(bean, domainEventsExchangeName);
        return new RabbitDomainEventBus(rSender);
    }

    private ReactiveMessageSender reactiveMessageSender(RabbitConnectionFactoryBean bean, String exchange) throws Exception {
        final ConnectionFactory factory = bean.getObject();
        factory.useNio();
        final Sender sender = ReactorRabbitMq.createSender(new SenderOptions().connectionFactory(factory));
        return new ReactiveMessageSender(sender, exchange, appName);
    }

    @Bean
    public Queue mainQueue() {
        return new Queue(appName, true);
    }

    @Bean
    public RabbitDirectAsyncGateway rabbitDirectAsyncGateway(RabbitConnectionFactoryBean bean,BrokerConfig config, ReactiveReplyRouter router) throws Exception {
        final ReactiveMessageSender rSender =  reactiveMessageSender(bean, directMessagesExchangeName);
        return new RabbitDirectAsyncGateway(config, router, rSender);
    }

    @Bean
    public ApplicationCommandListener commandListener(RabbitTemplate template, Environment env, ApplicationContext context, RabbitConnectionFactoryBean bean) throws Exception {
        final ConnectionFactory factory = bean.getObject();
        factory.useNio();
        final Receiver receiver = ReactorRabbitMq.createReceiver(new ReceiverOptions().connectionFactory(factory));
        return new ApplicationCommandListener(template, receiver, appName, env, context);
    }

    @Bean
    public Exchange globalReplyExchange() {
        return new TopicExchange("globalReply", true, false);
    }

    @Bean
    public Exchange globalDirectExchange() {
        return new DirectExchange(directMessagesExchangeName);
    }

    @Bean
    public Exchange businessEventsExchange() {
        return new TopicExchange(domainEventsExchangeName, true, false);
    }


    @Bean
    public Binding replyBinding(BrokerConfig config) {
        System.out.println("Configurando Bindig");
        return BindingBuilder.bind(replyQueue()).to(globalReplyExchange()).with(config.getRoutingKey()).noargs();
    }

    @Bean
    public Binding mainQueueBinding() {
        return BindingBuilder.bind(mainQueue()).to(globalDirectExchange()).with(appName).noargs();
    }

    @Bean
    public BrokerConfig brokerConfig(){
        return new BrokerConfig();
    }

    @Bean
    public Listener msgListener(ReactiveReplyRouter router){
        return new Listener(router);
    }


    @Bean
    RabbitConnectionFactoryBean getRabbitConnectionFactoryBean(RabbitProperties properties) throws Exception {
        PropertyMapper map = PropertyMapper.get();
        RabbitConnectionFactoryBean factory = new RabbitConnectionFactoryBean();
        map.from(properties::determineHost).whenNonNull().to(factory::setHost);
        map.from(properties::determinePort).to(factory::setPort);
        map.from(properties::determineUsername).whenNonNull().to(factory::setUsername);
        map.from(properties::determinePassword).whenNonNull().to(factory::setPassword);
        map.from(properties::determineVirtualHost).whenNonNull().to(factory::setVirtualHost);
        map.from(properties::getRequestedHeartbeat).whenNonNull().asInt(Duration::getSeconds).to(factory::setRequestedHeartbeat);
        RabbitProperties.Ssl ssl = properties.getSsl();
        if (ssl.isEnabled()) {
            factory.setUseSSL(true);
            ssl.getClass();
            map.from(ssl::getAlgorithm).whenNonNull().to(factory::setSslAlgorithm);
            ssl.getClass();
            map.from(ssl::getKeyStoreType).to(factory::setKeyStoreType);
            ssl.getClass();
            map.from(ssl::getKeyStore).to(factory::setKeyStore);
            ssl.getClass();
            map.from(ssl::getKeyStorePassword).to(factory::setKeyStorePassphrase);
            ssl.getClass();
            map.from(ssl::getTrustStoreType).to(factory::setTrustStoreType);
            ssl.getClass();
            map.from(ssl::getTrustStore).to(factory::setTrustStore);
            ssl.getClass();
            map.from(ssl::getTrustStorePassword).to(factory::setTrustStorePassphrase);
            ssl.getClass();
            map.from(ssl::isValidateServerCertificate).to((validate) -> {
                factory.setSkipServerCertificateValidation(!validate);
            });
            ssl.getClass();
            map.from(ssl::getVerifyHostname).to(factory::setEnableHostnameVerification);
        }

        map.from(properties::getConnectionTimeout).whenNonNull().asInt(Duration::toMillis).to(factory::setConnectionTimeout);
        factory.setUseNio(true);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    public ReactiveReplyRouter router(){
        return new ReactiveReplyRouter();
    }

    public static class Listener {

        private final ReactiveReplyRouter router;

        private Listener(ReactiveReplyRouter router) {
            this.router = router;
        }

        @RabbitListener(queues="#{replyQueue}", concurrency = "5")
        public void receive(Message<String> message) {
            String correlationID = message.getHeaders().get("x-correlation-id", String.class);
            if(message.getHeaders().containsKey("x-exception")){
                router.routeError(correlationID, message.getPayload());
            }else{
                router.routeReply(correlationID, message.getPayload());
            }
        }
    }

    public static class BrokerConfig{
        private final String routingKey = UUID.randomUUID().toString().replaceAll("-", "");
        public String getRoutingKey() {
            return routingKey;
        }
    }

    public static class ReactiveReplyRouter {

        private final ConcurrentHashMap<String, UnicastProcessor<String>> processors = new ConcurrentHashMap<>();

        public Mono<String> register(String correlationID){
            final UnicastProcessor<String> processor = UnicastProcessor.create(Queues.<String>one().get());
            processors.put(correlationID, processor);
            return processor.singleOrEmpty();
        }

        public void routeReply(String correlationID, String data){
            final UnicastProcessor<String> processor = processors.remove(correlationID);
            if(processor != null){
                processor.onNext(data);
                processor.onComplete();
            }
        }

        public <E> void routeError(String correlationID, String data){
            final UnicastProcessor<String> processor = processors.remove(correlationID);
            if(processor != null){
                processor.onError(new RuntimeException(data));
            }
        }

    }

}
