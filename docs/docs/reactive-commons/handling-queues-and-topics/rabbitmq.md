---
sidebar_position: 1
---

# RabbitMQ

## HandlerRegistry configuration

To get direct access to the underlying broker, bypassing the domain event / notification conventions, register a raw
listener in the `HandlerRegistry` and make it available as a Bean. The handler receives the raw message exactly as it
travels on the broker.

`HandlerRegistry.listenQueue(...)` is enabled by `@EnableQueueListeners` and only applies to RabbitMQ: the handler
receives a `RawMessage`, cast to `RabbitMessage`.

## Listening queues

The simplest way to listen to a queue is by providing the queue name and a handler:

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(QueueHandler queueHandler) {
        return HandlerRegistry.register()
                .listenQueue("my.custom.queue", queueHandler::handleMessage);
    }
}
```

To effectively start listening to queues you should add the annotation `@EnableQueueListeners` to your `MainApplication`
class or any other Spring Configuration class. The `QueueHandler` class can be like:

```java
@EnableQueueListeners
public class QueueHandler {

    public Mono<Void> handleMessage(RawMessage message) {
        RabbitMessage rawMessage = (RabbitMessage) message;
        System.out.println("Message received from queue: " + new String(rawMessage.getBody()));
        System.out.println("Headers: " + rawMessage.getProperties().getHeaders());
        // Process the message
        return Mono.empty();
    }
}
```

## Listening queues with custom topology

If you need to configure the queue topology (exchange type, durability, bindings, etc.), you can use the
`TopologyHandlerSetup` parameter. It receives RabbitMQ's own `TopologyCreator`, so it is cast to that type:

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(QueueHandler queueHandler) {
        return HandlerRegistry.register()
                .listenQueue("my.custom.queue", queueHandler::handleMessage, topologyCreator -> {
                    var creator = (TopologyCreator) topologyCreator; // org.reactivecommons.async.rabbit...

                    var exchangeSpecification = ExchangeSpecification
                            .exchange("myExchange")
                            .durable(true)
                            .type("topic");

                    var queueSpecification = QueueSpecification.queue("my.custom.queue")
                            .durable(false)
                            .autoDelete(true)
                            .exclusive(true)
                            .arguments(Map.of(
                                    "x-message-ttl", 60000,
                                    "x-max-length", 1000
                            ));

                    var bind = creator.bind(
                            BindingSpecification.binding("myExchange", "my.custom.queue", "my.custom.queue")
                    );

                    return creator.declare(exchangeSpecification)
                            .then(creator.declare(queueSpecification))
                            .then(bind)
                            .then();
                });
    }
}
```

The `TopologyHandlerSetup` allows you to:

- Declare queues with custom arguments (TTL, max-length, dead-letter exchange, etc.)
- Declare exchanges (direct, topic, fanout, headers)
- Create bindings between queues and exchanges
- Set queue types (classic, quorum)
- Set queue properties like durability, auto-delete, and exclusivity

If the domain's `createTopology` switch is `false`, the setup is never invoked and the topology is assumed to already
exist.

## Listening queues with custom domain

You can listen to queues in different domains by specifying the domain name:

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(QueueHandler queueHandler) {
        return HandlerRegistry.register()
                .listenQueue("customDomain", "my.custom.queue", queueHandler::handleMessage);
    }
}
```

## Queue configuration examples

### Dead letter queue configuration

Configure a queue with a dead letter exchange for failed messages:

```java
.listenQueue("main.queue",queueHandler::handleMessage, topologyCreator ->{
    var creator = (TopologyCreator) topologyCreator;
    
    var mainQueue = QueueSpecification.queue("main.queue")
            .durable(true)
            .arguments(Map.of(
                    "x-dead-letter-exchange", "dlx.exchange",
                    "x-dead-letter-routing-key", "main.queue.dlq"
            ));
    
    var dlxExchange = ExchangeSpecification.exchange("dlx.exchange")
            .type("direct")
            .durable(true);
    
    var dlqQueue = QueueSpecification.queue("main.queue.dlq")
            .durable(true)
            .arguments(Map.of(
                    "x-message-ttl", 60000
            ));
    
    var dlqBinding = creator.bind(
            BindingSpecification.binding("dlx.exchange", "main.queue.dlq", "main.queue.dlq")
    );

    return creator
        .declare(mainQueue)
        .then(creator.declare(dlxExchange))
        .then(creator.declare(dlqQueue))
        .then(dlqBinding)
        .then();
})
```

### Priority queue configuration

Configure a priority queue:

```java
.listenQueue("priority.queue",queueHandler::handleMessage, topologyCreator ->{
var creator = (TopologyCreator) topologyCreator;

var queueSpec = QueueSpecification.queue("priority.queue")
        .durable(true)
        .arguments(Map.of("x-max-priority", 10));

    return creator.

declare(queueSpec).then();
})
```

### Quorum queue configuration

Configure a quorum queue for high availability:

```java
.listenQueue("quorum.queue",queueHandler::handleMessage, topologyCreator ->{
var creator = (TopologyCreator) topologyCreator;

var queueSpec = QueueSpecification.queue("quorum.queue")
        .durable(true)
        .arguments(Map.of(
                "x-queue-type", "quorum",
                "x-quorum-initial-group-size", 3
        ));

    return creator.

declare(queueSpec).then();
})
```

### Temporary queue configuration

Configure a temporary queue with a random name for short-lived, exclusive connections. Temporary queues are useful for
reply-to patterns or ephemeral consumers:

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(QueueHandler queueHandler) {
        String queueName = "temp.queue.".concat(generateRandomQueueName());

        return HandlerRegistry.register()
                .listenQueue(queueName, queueHandler::handleMessage, topologyCreator -> {
                    var creator = (TopologyCreator) topologyCreator;
                    String exchangeName = "temp.exchange";

                    var exchangeSpec = ExchangeSpecification.exchange(exchangeName)
                            .type("topic")
                            .durable(true);

                    var queueSpec = QueueSpecification.queue(queueName)
                            .durable(false)
                            .autoDelete(true)
                            .exclusive(true);

                    var binding = creator.bind(
                            BindingSpecification.binding(exchangeName, queueName, queueName)
                    );

                    return creator.declare(exchangeSpec)
                            .then(creator.declare(queueSpec))
                            .then(binding)
                            .then();
                });
    }

    private String generateRandomQueueName() {
        UUID uuid = UUID.randomUUID();
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits());
        // Convert to base64 and remove trailing =
        return encodeToUrlSafeString(bb.array())
                .replace("=", "");
    }

    private static String encodeToUrlSafeString(byte[] src) {
        return new String(encodeUrlSafe(src));
    }

    private static byte[] encodeUrlSafe(byte[] src) {
        if (src.length == 0) {
            return src;
        }
        return Base64.getUrlEncoder().encode(src);
    }
}
```
