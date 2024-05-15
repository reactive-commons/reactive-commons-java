---
sidebar_position: 2
---

# Sending a Domain Event

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

     //... other definitions for eda variant
}
```

## Enabling autoconfiguration

To send Domain Events you should enable the respecting spring boot autoconfiguration using the `@EnableDomainEventBus` annotation
For example:

```java
@RequiredArgsConstructor
@EnableDomainEventBus
public class ReactiveEventsGateway {
    public static final String SOME_EVENT_NAME = "some.event.name";
    private final DomainEventBus domainEventBus; // Auto injectec bean created by the @EnableDomainEventBus annotation

    public Mono<Void> emit(Object event) {
         return Mono.from(domainEventBus.emit(new DomainEvent<>(SOME_EVENT_NAME, UUID.randomUUID().toString(), event)));
    }
}
```

After that you can emit events from you application.

## Example

You can see a real example at [samples/async/async-sender-client](https://github.com/reactive-commons/reactive-commons-java/tree/master/samples/async/async-sender-client)