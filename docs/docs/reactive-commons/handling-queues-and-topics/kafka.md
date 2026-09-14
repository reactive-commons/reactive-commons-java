---
sidebar_position: 2
---

# Kafka

## HandlerRegistry configuration

To get direct access to the underlying broker, bypassing the domain event / notification conventions, register a raw
listener in the `HandlerRegistry` and make it available as a Bean. The handler receives the raw message exactly as it
travels on the broker.

`HandlerRegistry.listenTopic(...)` is enabled by `@EnableTopicListeners` and only applies to Kafka: the handler
receives a `RawMessage`, cast to `KafkaMessage`.

## Listening topics

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

To effectively start listening to topics you should add the annotation `@EnableTopicListeners` to your `MainApplication`
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
## How a topic is consumed

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
[Kafka connection properties](../configuration_properties/2-kafka.md)). Without an explicit `group-id`, the topic
listeners fall back to `<appName>` and the domain events listener to `<appName>-events`.

## Listening topics with custom topology

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

## Listening topics with custom domain

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
