---
sidebar_position: 8
---

# Handling Queues

## HandlerRegistry configuration

To listen to a custom queue you should register it in the HandlerRegistry and make it available as a Bean. Queue listeners provide direct access to RabbitMQ queues with full control over queue configuration and topology.

### Listening Queues

The simplest way to listen to a queue is by providing the queue name and a handler:

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(QueueHandler queueHandler) {
        return HandlerRegistry.register()
                .listenQueue("my.custom.queue", queueHandler::handleQueueMessage);
    }
}
```

To effectively start listening to queues you should add the annotation `@EnableQueueListeners` to your MainApplication class or any other Spring Configuration class. The `QueueHandler` class can be like:

```java
@EnableQueueListeners
public class QueueHandler {
    
    public Mono<Void> handleQueueMessage(RawMessage message) {
        RabbitMessage rawMessage = (RabbitMessage) message;
        System.out.println("Message received from queue: " + new String(rawMessage.getBody()));
        System.out.println("Headers: " + rawMessage.getProperties().getHeaders());
        // Process the message
        return Mono.empty();
    }
}
```

### Listening Queues with Custom Topology

If you need to configure the queue topology (exchange type, durability, bindings, etc.), you can use the `TopologyHandlerSetup` parameter:

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(QueueHandler queueHandler) {
        return HandlerRegistry.register()
                .listenQueue("my.custom.queue", queueHandler::handleQueueMessage, topologyCreator -> {
                    var creator = (TopologyCreator) topologyCreator;
                    
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

### Listening Queues with Custom Domain

You can listen to queues in different domains by specifying the domain name:

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(QueueHandler queueHandler) {
        return HandlerRegistry.register()
                .listenQueue("customDomain", "my.custom.queue", queueHandler::handleQueueMessage);
    }
}
```

## Queue Configuration Examples

### Dead Letter Queue Configuration

Configure a queue with a dead letter exchange for failed messages:

```java
.listenQueue("main.queue", handler::handleMessage, topologyCreator -> {
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
    
    return creator.declare(mainQueue)
            .then(creator.declare(dlxExchange))
            .then(creator.declare(dlqQueue))
            .then(dlqBinding)
            .then();
})
```

### Priority Queue Configuration

Configure a priority queue:

```java
.listenQueue("priority.queue", handler::handleMessage, topologyCreator -> {
    var creator = (TopologyCreator) topologyCreator;
    
    var queueSpec = QueueSpecification.queue("priority.queue")
            .durable(true)
            .arguments(Map.of("x-max-priority", 10));
    
    return creator.declare(queueSpec).then();
})
```

### Quorum Queue Configuration

Configure a quorum queue for high availability:

```java
.listenQueue("quorum.queue", handler::handleMessage, topologyCreator -> {
    var creator = (TopologyCreator) topologyCreator;
    
    var queueSpec = QueueSpecification.queue("quorum.queue")
            .durable(true)
            .arguments(Map.of(
                "x-queue-type", "quorum",
                "x-quorum-initial-group-size", 3
            ));
    
    return creator.declare(queueSpec).then();
})
```
