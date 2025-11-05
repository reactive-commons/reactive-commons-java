---
sidebar_position: 5
---

# Handler Registry

Before start handling DomainEvents, Commands or Async Queries you should know a HandlerRegistry abstraction which enables you the ability to register what kind of these items can handle or serve.

## API specification

### HandlerRegistry

This abstraction allows you to register all kind of events, commands, queries and notification events that your application can handle, for each of them there is a method.

The next methods are the main methods that you can use to register a handler.

```java
public class HandlerRegistry {
    // events: DomainEvent
    public <T> HandlerRegistry listenEvent(String eventName, DomainEventHandler<T> handler, Class<T> eventClass)
    public <T> HandlerRegistry listenDomainEvent(String domain, String eventName, DomainEventHandler<T> handler, Class<T> eventClass)

    // events: CloudEvent
    public HandlerRegistry listenCloudEvent(String eventName, CloudEventHandler handler)
    public HandlerRegistry listenDomainCloudEvent(String domain, String eventName, CloudEventHandler handler)

    // events: RawMessage
    public HandlerRegistry listenRawEvent(String eventName, RawEventHandler<?> handler)
    public HandlerRegistry listenDomainRawEvent(String domain, String eventName, RawEventHandler<?> handler)

    // notifications: DomainEvent
    public <T> HandlerRegistry listenNotificationEvent(String eventName, DomainEventHandler<T> handler, Class<T> eventClass)
    public <T> HandlerRegistry listenNotificationEvent(String domain, String eventName, DomainEventHandler<T> handler, Class<T> eventClass)

    // notifications: CloudEvent
    public HandlerRegistry listenNotificationCloudEvent(String eventName, CloudEventHandler handler)
    public HandlerRegistry listenNotificationCloudEvent(String domain, String eventName, CloudEventHandler handler)

    // notifications: RawMessage
    public HandlerRegistry listenNotificationRawEvent(String eventName, RawEventHandler<?> handler)
    public HandlerRegistry listenNotificationRawEvent(String domain, String eventName, RawEventHandler<?> handler)

    // dynamic: DomainEvent supported only for default domain
    public <T> HandlerRegistry handleDynamicEvents(String eventNamePattern, DomainEventHandler<T> handler, Class<T> eventClass)

    // dynamic: CloudEvent supported only for default domain
    public HandlerRegistry handleDynamicCloudEvents(String eventNamePattern, CloudEventHandler handler)

    // commands: Command
    public <T> HandlerRegistry handleCommand(String commandName, DomainCommandHandler<T> fn, Class<T> commandClass)
    public <T> HandlerRegistry handleCommand(String domain, String commandName, DomainCommandHandler<T> fn, Class<T> commandClass)

    // commands: CloudEvent
    public HandlerRegistry handleCloudEventCommand(String commandName, CloudCommandHandler handler)
    public HandlerRegistry handleCloudEventCommand(String domain, String commandName, CloudCommandHandler handler)

    // commands: RawMessage
    public HandlerRegistry handleRawCommand(RawCommandHandler<?> handler)
    public HandlerRegistry handleRawCommand(String domain, RawCommandHandler<?> handler)

    // queries: Query
    public <T, R> HandlerRegistry serveQuery(String resource, QueryHandler<T, R> handler, Class<R> queryClass)
    public <R> HandlerRegistry serveQuery(String resource, QueryHandlerDelegate<Void, R> handler, Class<R> queryClass)
    public <R> HandlerRegistry serveCloudEventQuery(String resource, QueryHandler<R, CloudEvent> handler)
    public HandlerRegistry serveCloudEventQuery(String resource, QueryHandlerDelegate<Void, CloudEvent> handler)
}
```

Methods that Has `CloudEvent` in the name are related to the CloudEvent specification.

Methods that has `domain` String argument are related to the multi-broker support, this support is limited to listen events
from different domains (brokers) independent of the technology.