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

### Wildcards

You may need to listen variable event names that have the same structure, in that case you have the method `handleDynamicEvents` in the `HandlerRegistry`, so you can specfy a pattern with '*' wildcard, it does not creates a binding in the broker, but allows that you do it dynamically through a `DynamicRegistry` class.

#### DynamicRegistry API

```java
public interface DynamicRegistry {

    Mono<Void> startListeningEvent(String eventName);

    Mono<Void> stopListeningEvent(String eventName);

    //... other definitions for queries

}
```

To start listening a new event dynamically at runtime, you should inject and call a method of the DynamicReggistry, for example:

```java
@Component
@AllArgsConstructor
public class DynamicSubscriber {
    private final DynamicRegistry registry;

    public Mono<Void> listenNewEvent(String eventName) {
       return registry.startListeningEvent(eventName);
    }
}
```

The conditions for a success dynamic registry functionality are:

- You should handle dynamic events with specific wildcard

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(EventsHandler events) {
        return HandlerRegistry.register()
                .handleDynamicEvents("purchase.*", events::handleEventA, Object.class/*change for proper model*/);
    }
}
```

- Start a listener dynamically through

```java
registry.startListeningEvent("purchase.cancelled");
```

You also can listen with * wildcard or # wildcard, the * wildcard is for a single word and # wildcard is for multiple words, for example:

`animals.*` will listen to `animals.dog`, `animals.cat`, `animals.bird`, etc.
`animals.#` will listen to `animals.dog`, `animals.dog.bark`, `animals.cat`, `animals.cat.meow`, etc.

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(EventsHandler events) {
        return HandlerRegistry.register()
                .listenEvent("animals.*", events::handleEventA, Object.class/*change for proper model*/)
                .listenEvent("pets.#.any", events::handleEventA, Object.class/*change for proper model*/);
    }
}
```

## Example

You can see a real example at [samples/async/async-receiver-responder](https://github.com/reactive-commons/reactive-commons-java/tree/master/samples/async/async-receiver-responder)