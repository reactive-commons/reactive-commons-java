---
sidebar_position: 8
---

# Handling Queues and Topics

## HandlerRegistry configuration

To get direct access to the underlying broker, bypassing the domain event / notification conventions, register a raw
listener in the `HandlerRegistry` and make it available as a Bean. The handler receives the raw message exactly as it
travels on the broker.

There are **two separate methods**, one per broker, and they are **not interchangeable**:

| Method                             | Broker   | Enabled by              | What the name means                                      | What the handler receives                              |
|------------------------------------|----------|-------------------------|----------------------------------------------------------|--------------------------------------------------------|
| `HandlerRegistry.listenQueue(...)` | RabbitMQ | `@EnableQueueListeners` | The RabbitMQ queue itself                                | A `RabbitMessage`, cast from the `RawMessage` argument |
| `HandlerRegistry.listenTopic(...)` | Kafka    | `@EnableTopicListeners` | The Kafka **topic**, consumed by the group of the domain | A `KafkaMessage`, cast from the `RawMessage` argument  |

Registering `listenQueue` while running on Kafka (or `listenTopic` while running on RabbitMQ) has no effect: each
`BrokerProvider` only starts the listeners meant for its own broker, so the "wrong" one for the active broker is
silently never started. Use the method that matches the starter you depend on (`async-commons-rabbit-starter` or
`async-commons-kafka-starter`).

## Listening RabbitMQ Queues

### Listening queues

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

To effectively start listening to queues you should add the annotation `@EnableQueueListeners` to your MainApplication
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

### Listening queues with custom topology

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

### Listening queues with custom domain

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

## Listening Kafka Topics

### Listening topics

The simplest way to listen to a topic directly is by providing the topic name and a handler:

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(TopicHandler topicHandler) {
        return HandlerRegistry.register()
                .listenTopic("my.custom.topic", topicHandler::handleMessage);
    }
}
```

To effectively start listening to topics you should add the annotation `@EnableTopicListeners` to your MainApplication
class or any other Spring Configuration class. The `TopicHandler` class can be like:

```java
@EnableTopicListeners
public class TopicHandler {

    public Mono<Void> handleMessage(RawMessage message) {
        KafkaMessage rawMessage = (KafkaMessage) message;
        System.out.println("Message received from topic: " + new String(rawMessage.getBody()));
        System.out.println("Headers: " + rawMessage.getProperties().getHeaders());
        // Process the message
        return Mono.empty();
    }
}
```

### How a topic is consumed

Kafka has no native queue concept, so `listenTopic(...)` subscribes directly to that topic, using the **same consumer
group as the rest of the listeners of that domain**. Registering a topic listener therefore does not create another
consumer group: the group gains one subscription, and several instances of the application keep sharing the work of that
topic exactly as several consumers competing for the same RabbitMQ queue would.

The group id is the `group.id` configured under `connection-properties.consumer.group-id` for the domain when present,
the same one the domain events listener honours, and falls back to the application name otherwise.

```yaml title="application.yaml"
reactive:
  commons:
    kafka:
      app:
        connection-properties:
          consumer:
            group-id: my-service.consumer-group
```

With the configuration above, a topic registered as `my.custom.topic` is consumed by the group
`my-service.consumer-group`, which is also the group of the domain events listener (see
[Kafka connection properties](./configuration_properties/2-kafka.md)). Without an explicit `group-id`, the topic
listeners fall back to `<appName>` and the domain events listener to `<appName>-events`.

:::note One group, several subscriptions Each listener creates its own Kafka consumer, and all of them join the same
group with their own subscription. Kafka assigns the partitions of a topic only among the members subscribed to it, so
the listeners do not steal each other's records. What they do share is the rebalance: adding or removing any listener,
or
any instance, rebalances the whole group.
:::

:::caution Upgrading from a version that appended the topic name Earlier versions consumed each topic listener with
`<base>-<topic>`. Moving to the shared group id means the new group starts with no committed offsets for that topic, so
`auto.offset.reset` decides what happens with the records the old group had not processed: `latest`, the Kafka default,
skips them, and `earliest` reprocesses the topic from its beginning. Check the lag of the old
`<base>-<topic>` group before deploying.
:::

Because the registered name is used as the topic name, it must be a valid Kafka topic name.

### Listening topics with custom topology

If you need to control how the topic is created (partitions, replication factor, configs), use the
`TopologyHandlerSetup` parameter. It receives Kafka's own `TopologyCreator`, so it is cast to that type:

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(TopicHandler topicHandler) {
        return HandlerRegistry.register()
                .listenTopic("my.custom.topic", topicHandler::handleMessage, topologyCreator -> {
                    var creator = (TopologyCreator) topologyCreator; // org.reactivecommons.async.kafka...
                    return creator.createTopics(List.of("my.custom.topic"));
                });
    }
}
```

`TopologyCreator.createTopics(List<String>)` honours any `KafkaCustomizations` (partitions, replication factor, topic
configs) already registered for that topic name. If the domain's `createTopology` switch is `false`, the setup is never
invoked and the topic is assumed to already exist.

### Listening topics with custom domain

You can listen to topics in different domains by specifying the domain name:

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(TopicHandler topicHandler) {
        return HandlerRegistry.register()
                .listenTopic("customDomain", "my.custom.topic", topicHandler::handleMessage);
    }
}
```

### One consumer per topic, one group for all of them

Each `listenTopic(...)` call starts its **own** consumer, and all of them join the consumer group of the domain (see
[How a topic is consumed](#how-a-topic-is-consumed)). Registering several topics does **not** make a single consumer
subscribe to all of them:

```java
// This starts TWO independent consumers inside the same consumer group,
// each subscribed to its own topic, not one consumer listening to both.
.listenTopic("topic.a", handlerA)
.listenTopic("topic.b", handlerB)
```

If what you need is a **single consumer subscribed to several topic names**, `listenTopic` is not the right tool:
use `listenEvent` / `listenDomainEvent` / `listenRawEvent` instead. All the names registered that way are read by one
consumer, in the domain events group (see [Kafka connection properties](./configuration_properties/2-kafka.md)),
and Kafka's own partition assignment decides which pod gets which record; internally, each message is still routed to
the handler that matches its topic name:

```java
@Bean
public HandlerRegistry handlerRegistry(EventsHandler events) {
    return HandlerRegistry.register()
            .listenEvent("event.push", events::handleEventA, MessagePush.class)
            .listenRawEvent("event.raw.thing", events::handleRawEvent);
}
```

`listenTopic` exists precisely for the opposite case: a topic that must stay isolated from the domain events convention,
with its own consumer group, its own topology and its own retry/DLQ behaviour.

## RabbitMQ queue configuration examples

These examples are RabbitMQ-specific: they use `listenQueue(...)` and the `ExchangeSpecification` /
`QueueSpecification` / `BindingSpecification` topology, none of which apply to Kafka. See
[Listening topics with custom topology](#listening-topics-with-custom-topology) for the Kafka equivalent.

### Dead letter queue configuration

Configure a queue with a dead letter exchange for failed messages:

```java
.listenQueue("main.queue", queueHandler::handleMessage, topologyCreator -> {
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

### Priority queue configuration

Configure a priority queue:

```java
.listenQueue("priority.queue", queueHandler::handleMessage, topologyCreator -> {
    var creator = (TopologyCreator) topologyCreator;
    
    var queueSpec = QueueSpecification.queue("priority.queue")
            .durable(true)
            .arguments(Map.of("x-max-priority", 10));
    
    return creator.declare(queueSpec).then();
})
```

### Quorum queue configuration

Configure a quorum queue for high availability:

```java
.listenQueue("quorum.queue", queueHandler::handleMessage, topologyCreator -> {
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
