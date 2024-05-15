---
sidebar_position: 3
---

# Additional API Definitions

Returning to the base API, the additional methods enabled for EDA and CloudEvents are indicated below.

## Aditional Methods in Senders

### DomainEventBus interface

This interface has one additional method specific for EDA

```java
public interface DomainEventBus {
    Publisher<Void> emit(CloudEvent event); // Emit with CloudEvent format

    //... other base definitions
}
```

### DirectAsyncGateway interface

Ths interface adds some actions for commands and async queries

```java
public interface DirectAsyncGateway {
    <T> Mono<Void> sendCommand(Command<T> command, String targetName, String domain); // Send to specific domain

    <T> Mono<Void> sendCommand(Command<T> command, String targetName, long delayMillis, String domain); // Send to specific domain with delay

    Mono<Void> sendCommand(CloudEvent command, String targetName); // Send with CloudEvent format

    Mono<Void> sendCommand(CloudEvent command, String targetName, String domain); // Send with CloudEvent format to an specific domain

    <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type, String domain); // Query to specific domain

    <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type); // Query with CloudEvent format

    <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type, String domain); // Query with CloudEvent format to specific domain
}
```

## Aditional Listener capabilities

On the other hand, for listener you can use additional capabilities like listen from another domain

### HandlerRegistry class

```java
public class HandlerRegistry {
    public <T> HandlerRegistry listenDomainEvent(String domain, String eventName, EventHandler<T> handler, Class<T> eventClass) {...} // Class could be CloudEvent.class

    // ... other base methods
}
```