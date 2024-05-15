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
    public <T> HandlerRegistry listenEvent(String eventName, EventHandler<T> handler, Class<T> eventClass){...}
    public <T> HandlerRegistry handleDynamicEvents(String eventNamePattern, EventHandler<T> handler, Class<T> eventClass){...}
    public <T> HandlerRegistry listenNotificationEvent(String eventName, EventHandler<T> handler, Class<T> eventClass){...}
    public <T> HandlerRegistry handleCommand(String commandName, CommandHandler<T> fn, Class<T> commandClass){...}
    public <T, R> HandlerRegistry serveQuery(String resource, QueryHandler<T, R> handler, Class<R> queryClass){...}
    public <R> HandlerRegistry serveQuery(String resource, QueryHandlerDelegate<Void, R> handler, Class<R> queryClass) {...}
    // ... other methods for eda variant and overloads
}
```