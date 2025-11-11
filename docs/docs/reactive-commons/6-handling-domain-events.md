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

### Listening Raw Events

If you need direct access to the raw message from RabbitMQ without domain model conversion, you can use `RawEventHandler`. 
This approach applies to both domain events and notification events. Raw event handlers process all incoming events for the specified event name,
giving you access to the message body, headers, and other low-level properties directly.

#### Example for Raw Domain Events

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(EventsHandler events) {
        return HandlerRegistry.register()
                .listenRawEvent("some.event.name", events::handleRawEventOrNotification)
                .listenNotificationRawEvent("some.notification.event", events::handleRawEventOrNotification);
    }
}
```

The handler implementation receives a `RawMessage` which can be cast to `RabbitMessage` to access the underlying message properties:

```java
@EnableEventListeners
@EnableNotificationListener
public class EventsHandler {

    public Mono<Void> handleRawEventOrNotification(RawMessage event) {
        RabbitMessage rawMessage = (RabbitMessage) event;
        System.out.println("RawEvent received: " + new String(rawMessage.getBody()));
        System.out.println("Content Type: " + rawMessage.getProperties().getContentType());
        System.out.println("Headers: " + rawMessage.getProperties().getHeaders());
        // Process the raw event or notification
        return Mono.empty();
    }

}
```
