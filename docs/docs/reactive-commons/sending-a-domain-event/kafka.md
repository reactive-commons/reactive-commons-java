---
sidebar_position: 2
---

# Kafka

## API specification

### DomainEvent model

To emit a Domain Event we need to know the DomainEvent structure, which is represented with the next class:

```java
public class DomainEvent<T> {
    private final String name;
    private final String eventId;
    private final T data;
}
```

Where name is the event name, eventId is an unique event identifier and data is a JSON Serializable payload.

### DomainEventBus interface

```java
public interface DomainEventBus {
    <T> Publisher<Void> emit(DomainEvent<T> event);

    <T> Publisher<Void> emit(String domain, DomainEvent<T> event);

    Publisher<Void> emit(CloudEvent event);

    Publisher<Void> emit(String domain, CloudEvent event);

    Publisher<Void> emit(RawMessage event);
    
    Publisher<Void> emit(String domain, RawMessage event);
}
```

## Enabling autoconfiguration

To send Domain Events you should enable the respecting spring boot autoconfiguration using the `@EnableDomainEventBus`
annotation For example:

```java
@RequiredArgsConstructor
@EnableDomainEventBus
public class ReactiveEventsGateway {
    public static final String SOME_EVENT_NAME = "some.event.name";
    private final DomainEventBus domainEventBus; // Auto injected bean created by the @EnableDomainEventBus annotation

    public Mono<Void> emit(Object event/*change for proper model*/) {
         return Mono.from(domainEventBus.emit(new DomainEvent<>(SOME_EVENT_NAME, UUID.randomUUID().toString(), event)));
    }
}
```

After that you can emit events from you application.

## Sending a Raw Message

`DomainEventBus.emit(RawMessage event)` bypasses the `DomainEvent` / `CloudEvent` conventions: instead of building a
generic envelope, you hand over the broker-specific message yourself, with full control over its body, routing and
headers.

For Kafka there is no separate routing key parameter: the **topic** and the partitioning key travel inside the
`KafkaMessage` itself, through `KafkaMessageProperties`:

```java
@RequiredArgsConstructor
@EnableDomainEventBus
public class ReactiveEventsGateway {
    private final JsonMapper jsonMapper;
    private final DomainEventBus domainEventBus;

    public Mono<Void> emitRaw(String accountId, Object payload/*change for proper model*/) {
        KafkaMessage.KafkaMessageProperties properties = new KafkaMessage.KafkaMessageProperties();
        properties.setTopic("some.event.name");         // required: this is where the record is published
        properties.setKey(accountId);                    // optional: Kafka partitioning key, may be null
        properties.getHeaders().put("content-type", "application/json");

        var rawMessage = new KafkaMessage(jsonMapper.writeValueAsBytes(payload), properties, null);
        return Mono.from(domainEventBus.emit(rawMessage));
    }
}
```

A header may have a `null` value: it is sent as a header without value. The other header values are sent as UTF-8
text, and a `byte[]` value as it is.

## Key of the record and ordering

The key of a record decides its partition, and Kafka keeps the order of the records of a partition. The key is:

| What you emit  | Key of the record                                                                        |
|----------------|------------------------------------------------------------------------------------------|
| `DomainEvent`  | Its `eventId`.                                                                           |
| `CloudEvent`   | Its `partitionkey` extension, as the Kafka binding of CloudEvents maps it; else its `id`. |
| `RawMessage`   | The key of its `KafkaMessageProperties`, which may be `null`.                            |

Events that must be handled in order, such as the events of the same entity, must have the same key. Since a
`DomainEvent` is keyed by its own id, publish them as a `CloudEvent` with the `partitionkey` extension (for example the
id of the entity), or as a `RawMessage` with that key:

```java
CloudEvent event = CloudEventBuilder.v1()
        .withId(UUID.randomUUID().toString())
        .withSource(URI.create("https://accounts.example.com"))
        .withType("account.updated")
        .withExtension("partitionkey", accountId)
        .withData("application/json", jsonMapper.writeValueAsBytes(payload))
        .build();
return Mono.from(domainEventBus.emit(event));
```

:::warning
A `CloudEvent` that already had the `partitionkey` extension was keyed by its `id` in previous versions: from this
version on its records go to the partition of its `partitionkey`.
:::

## When an emit fails

The `Mono` of an `emit` fails, and the error reaches your error handlers, when:

- The topic does not exist (`TopicNotFoundException`, with `checkExistingTopics: true`).
- The broker does not acknowledge the record within `sendTimeout` (`java.util.concurrent.TimeoutException`). The
  record may still be written.
- The sender already holds `maxPendingSends` sends waiting for their outcome (`SendQueueFullException`). Nothing was
  sent.
- The broker rejects the record, or the payload does not comply with its schema.

See [Publishing](../configuration_properties/2-kafka.md#publishing) for these properties.

## Operations not supported on Kafka

Commands, async queries and their replies (`DirectAsyncGateway`) are not supported on Kafka, nor the
`emit(String domain, ...)` methods of the Kafka event bus itself (the `DomainEventBus` bean routes them to the event
bus of each domain). They return a `Mono` that fails with `UnsupportedOperationException`: they do not throw when
called, so the error reaches the `onError` handlers of your chain.

## Example

You can see a real example
at [samples/async/async-sender-client](https://github.com/reactive-commons/reactive-commons-java/tree/master/samples/async/async-sender-client)
