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

All the topics registered with `listenTopic(...)` in a domain share one Kafka consumer, which gives each record to the
handler of its topic. Each topic keeps its own handler, its own topology setup and its own DLQ (`<topic>.dlq`). The
consumer starts once the application context is refreshed (see
[Consumers of a domain](../configuration_properties/2-kafka.md#consumers-of-a-domain)).

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
                    var creator = (TopologyCreator) topologyCreator;
                    return creator.createTopics(List.of("my.custom.topic"));
                });
    }
}
```

`TopologyCreator.createTopics(List<String>)` honours any `KafkaCustomizations` (partitions, replication factor, topic
configs) already registered for that topic name. If the domain's `createTopology` switch is `false`, the setup is never
invoked and the topic is assumed to already exist.

## Sending the discarded messages to a DLQ

With `withDLQRetry: true`, a message of a topic that exhausted its retries is sent, as it is (key, body and headers), to
the DLQ of its topic, `<topic>.dlq`, in the same domain. Its offset is committed once it is there.

The topology setup of the listener owns the whole topology, so it must create that topic too:

```java
.listenTopic("my.custom.topic", topicHandler::handleMessage, topologyCreator -> {
    var creator = (TopologyCreator) topologyCreator;
    return creator.createTopics(List.of("my.custom.topic"))
            .then(creator.createDlqTopics(List.of("my.custom.topic")));
})
```

While the message cannot be sent to its DLQ (the topic does not exist, the broker is unreachable, the send does not
complete within `sendTimeout`, or the sender already holds `maxPendingSends` sends), it is kept and sent again, every
few seconds, and the messages after it in its partition wait. A message too large for the DLQ is
logged and skipped.

Besides its own headers, the message in the DLQ carries the topic, partition and offset it was read from and the
failure that discarded it, in the `x-dlq-*` headers (see
[Headers of a message in a DLQ](../configuration_properties/2-kafka.md#headers-of-a-message-in-a-dlq)). A record
without key, and a header without value, are copied to the DLQ as they are.

## Records without value (tombstones)

A raw topic may be a compacted one, where a record without value (a tombstone) deletes its key. The handler receives
it like any other record, so it can delete what it keeps for that key: its body is `null` and its content length is 0.

```java
public Mono<Void> handleMessage(RawMessage message) {
    KafkaMessage rawMessage = (KafkaMessage) message;
    if (rawMessage.getBody() == null) {
        return repository.delete(rawMessage.getProperties().getKey());
    }
    // Process the message
    return Mono.empty();
}
```

A header without value is received with a `null` value. The header values are read as UTF-8 text.

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
