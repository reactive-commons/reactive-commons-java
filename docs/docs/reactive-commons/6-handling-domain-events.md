---
sidebar_position: 6
---

# Handling DomainEvents

## HandlerRegistry configuration

To listen a DomainEvent you should register it in the HandlerRegistry and make it available as a Bean

### Listening Events

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(EventsHandler events) {
        return HandlerRegistry.register()
                .listenEvent("some.event.name", events::handleEventA, Object.class/*change for proper model*/);
    }
}
```

To effectively start listening events you should add the annotation `@EnableEventListeners` to your MainApplication class or any other spring Configuration class, for example the `EventsHandler` class can be like:

```java
@EnableEventListeners
public class EventsHandler {
    public Mono<Void> handleEventA(DomainEvent<Object/*change for proper model*/> event) {
        System.out.println("event received: " + event.getName() + " ->" + event.getData());
        return Mono.empty();
    }
}
```

### Listening Notification Events (broadcast)

In the same way you can listen the NotificationEvents which has the same DomainEvent definition, but in that case you should add the `@EnableNotificationListener` annotation 

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(EventsHandler events) {
        return HandlerRegistry.register()
                .listenNotificationEvent("some.broadcast.event.name", events::handleEventA, Object.class/*change for proper model*/);
    }
}
```

Then you should create the handler like:

```java
@EnableNotificationListener
public class EventsHandler {
    public Mono<Void> handleEventA(DomainEvent<Object/*change for proper model*/> event) {
        System.out.println("event received: " + event.getName() + " ->" + event.getData());
        return Mono.empty();
    }
}
```