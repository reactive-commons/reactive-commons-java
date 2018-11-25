package org.reactivecommons.async.impl.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.DefaultQueryHandler;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.MessageConverter;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.RabbitDirectAsyncGateway;
import org.reactivecommons.async.impl.RabbitDomainEventBus;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.converters.JacksonMessageConverter;
import org.reactivecommons.async.impl.listeners.ApplicationCommandListener;
import org.reactivecommons.async.impl.listeners.ApplicationEventListener;
import org.reactivecommons.async.impl.listeners.ApplicationQueryListener;
import org.reactivecommons.async.impl.listeners.ApplicationReplyListener;
import org.reactivecommons.async.impl.reply.ReactiveReplyRouter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.util.Base64Utils;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


@Configuration
@Import(RabbitMqConfig.class)
public class MessageConfig {

    @Value("${spring.application.name}")
    private String appName;

    @Value("${app.async.domain.events.exchange:domainEvents}")
    private String domainEventsExchangeName;

    @Value("${app.async.direct.exchange:directMessages}")
    private String directMessagesExchangeName;


    public String generateName() {
        UUID uuid = UUID.randomUUID();
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits())
            .putLong(uuid.getLeastSignificantBits());
        // Convert to base64 and remove trailing =
        return this.appName + Base64Utils.encodeToUrlSafeString(bb.array())
            .replaceAll("=", "");
    }


    @Bean
    public DomainEventBus domainEventBus(MessageConverter converter, ReactiveMessageSender rSender) throws Exception {
        return new RabbitDomainEventBus(rSender, domainEventsExchangeName);
    }


    @Bean
    public RabbitDirectAsyncGateway rabbitDirectAsyncGateway(BrokerConfig config, ReactiveReplyRouter router, MessageConverter converter, ReactiveMessageSender rSender) throws Exception {
        return new RabbitDirectAsyncGateway(config, router, rSender, directMessagesExchangeName);
    }

    @Bean
    public ApplicationQueryListener queryListener(MessageConverter converter, HandlerResolver resolver, ReactiveMessageSender sender, ReactiveMessageListener rlistener) throws Exception {
        final ApplicationQueryListener listener = new ApplicationQueryListener(rlistener, appName+".query", resolver, sender, directMessagesExchangeName, converter, "globalReply");
        listener.startListener();
        return listener;
    }

    @Bean
    public ApplicationCommandListener applicationCommandListener(ReactiveMessageListener listener, HandlerResolver resolver, MessageConverter converter){
        ApplicationCommandListener commandListener = new ApplicationCommandListener(listener, appName, resolver, directMessagesExchangeName, converter);
        commandListener.startListener();
        return commandListener;
    }


//    private ReactiveMessageListener messageListener() {
//        final Mono<Channel> channelMono = connection.map(c -> {
//            try {
//                return c.createChannel();
//            } catch (IOException e) {
//                throw new RuntimeException(e);
//            }
//        });
//        Sender sender = ReactorRabbitMq.createSender(new SenderOptions().connectionMono(connection).resourceManagementChannelMono(channelMono));
//        Receiver receiver = ReactorRabbitMq.createReceiver(new ReceiverOptions().connectionMono(connection).channelMono(channelMono));
//        return new ReactiveMessageListener(sender, receiver);
//    }

    @Bean
    @ConditionalOnMissingBean
    public MessageConverter messageConverter(){
        return new JacksonMessageConverter(new ObjectMapper());
    }

    @Bean
    public ApplicationEventListener eventListener(HandlerResolver resolver, MessageConverter messageConverter, ReactiveMessageListener receiver) throws Exception {
        final ApplicationEventListener listener = new ApplicationEventListener(receiver, appName + ".subsEvents", resolver, domainEventsExchangeName, messageConverter);
        listener.startListener();
        return listener;
    }

    @Bean
    public HandlerResolver resolver(ApplicationContext context, DefaultQueryHandler defaultHandler, Environment env, DefaultCommandHandler defaultCommandHandler) {
        final Map<String, HandlerRegistry> registries = context.getBeansOfType(HandlerRegistry.class);

        final ConcurrentHashMap<String, QueryHandler<?, ?>> handlers = registries
            .values().stream()
            .flatMap(r -> r.getHandlers().stream())
            .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler.getHandler()),
                ConcurrentHashMap::putAll);

        final Map<String, HandlerRegistry.RegisteredEventListener> eventListeners = registries
            .values().stream()
            .flatMap(r -> r.getEventListeners().stream())
            .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                ConcurrentHashMap::putAll);

        final Map<String, HandlerRegistry.RegisteredCommandHandler> commandHandlers = registries
            .values().stream()
            .flatMap(r -> r.getCommandHandlers().stream())
            .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                ConcurrentHashMap::putAll);

        return new HandlerResolver(handlers, eventListeners, commandHandlers) {
            @Override
            @SuppressWarnings("unchecked")
            public QueryHandler<?, ?> getQueryHandler(String path) {
                final QueryHandler<?, ?> handler = super.getQueryHandler(path);
                if (handler == null) {
                    try {
                        final String handlerName = env.getProperty(path);
                        return context.getBean(handlerName, QueryHandler.class);
                    } catch (Exception e) {
                        return defaultHandler;
                    }
                }
                return handler;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> HandlerRegistry.RegisteredCommandHandler<T> getCommandHandler(String path) {
                final HandlerRegistry.RegisteredCommandHandler<T> handler = super.getCommandHandler(path);
                return handler != null ? handler : new HandlerRegistry.RegisteredCommandHandler<>("", defaultCommandHandler, Object.class);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultQueryHandler defaultHandler() {
        return (DefaultQueryHandler<Object, Object>) command ->
            Mono.error(new RuntimeException("No Handler Registered"));
    }


    @Bean
    @ConditionalOnMissingBean
    public DefaultCommandHandler defaultCommandHandler(){
        return message -> Mono.error(new RuntimeException("No Handler Registered"));
    }


    @Bean
    public BrokerConfig brokerConfig() {
        return new BrokerConfig();
    }

    @Bean
    public ApplicationReplyListener msgListener(ReactiveReplyRouter router, BrokerConfig config, ReactiveMessageListener listener)  {
        final ApplicationReplyListener replyListener = new ApplicationReplyListener(router, listener, generateName());
        replyListener.startListening(config.getRoutingKey());
        return replyListener;
    }


    @Bean
    public ReactiveReplyRouter router() {
        return new ReactiveReplyRouter();
    }


    public static class BrokerConfig {
        private final String routingKey = UUID.randomUUID().toString().replaceAll("-", "");

        public String getRoutingKey() {
            return routingKey;
        }
    }


}
