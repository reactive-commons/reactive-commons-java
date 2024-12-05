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
    public <T> HandlerRegistry listenDomainEvent(String domain, String eventName, DomainEventHandler<T> handler, Class<T> eventClass)
    public HandlerRegistry listenDomainCloudEvent(String domain, String eventName, CloudEventHandler handler)
    public <T> HandlerRegistry listenEvent(String eventName, DomainEventHandler<T> handler, Class<T> eventClass)
    public HandlerRegistry listenCloudEvent(String eventName, CloudEventHandler handler)
    public <T> HandlerRegistry listenNotificationEvent(String eventName, DomainEventHandler<T> handler, Class<T> eventClass)
    public HandlerRegistry listenNotificationCloudEvent(String eventName, CloudEventHandler handler)
    public <T> HandlerRegistry handleDynamicEvents(String eventNamePattern, DomainEventHandler<T> handler, Class<T> eventClass)
    public HandlerRegistry handleDynamicCloudEvents(String eventNamePattern, CloudEventHandler handler)
    public <T> HandlerRegistry handleCommand(String commandName, DomainCommandHandler<T> fn, Class<T> commandClass)
    public HandlerRegistry handleCloudEventCommand(String commandName, CloudCommandHandler handler)
    public <T, R> HandlerRegistry serveQuery(String resource, QueryHandler<T, R> handler, Class<R> queryClass)
    public <R> HandlerRegistry serveQuery(String resource, QueryHandlerDelegate<Void, R> handler, Class<R> queryClass)
    public <R> HandlerRegistry serveCloudEventQuery(String resource, QueryHandler<R, CloudEvent> handler)
    public <R> HandlerRegistry serveCloudEventQuery(String resource, QueryHandlerDelegate<Void, CloudEvent> handler)
}
```

Methods that Has `CloudEvent` in the name are related to the CloudEvent specification.

Methods that has `domain` String argument are related to the multi-broker support, this support is limited to listen events
from different domains (brokers) independent of the technology.