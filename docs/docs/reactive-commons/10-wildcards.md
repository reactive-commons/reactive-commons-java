---
sidebar_position: 10
---

# Wildcards

You may need to listen variable event names that have the same structure, in that case you have the method `handleDynamicEvents` in the `HandlerRegistry`, so you can specify a pattern with '*' wildcard, it does not creates a binding in the broker, but allows that you do it dynamically through a `DynamicRegistry` class.

You can also create binding with '#' wildcard, it is used to listen multiple words, for example `animals.#` will listen to `animals.dog`, `animals.dog.bark`, `animals.cat`, `animals.cat.meow`, etc.

## DynamicRegistry API

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

This last approach is useful when you have a dynamic event name, for example, you can have a `purchase.cancelled` event, but you can also have a `purchase.cancelled.2021` event, so you can listen to all of them with `purchase.*` or `purchase.#` respectively.

## Priorities

The handlers with wildcards have the lowest priority, so if you have a specific handler for an event name, it will be called before the wildcard handler.

The wildcard handler will be called if there is no specific handler for the event name. And the wildcard matches the pattern.

General conditions for handler priority are:
- fixed words has priority over wildcard
- wildcard with * has priority over wildcard with #
- wildcard with # has the lowest priority

The next code will help you to avoid unexpected behaviors, which indicates you the handler that will be called.
```java
    public static void main(String[] args) {
        Set<String> names = Set.of("prefix.*.*", "prefix.*.#");
        String target = "prefix.middle.suffix";
        String handler = new KeyMatcher().match(names, target);
        System.out.println(handler);
    }
```

## Example

You can see a real example at [samples/async/async-receiver-responder](https://github.com/reactive-commons/reactive-commons-java/tree/master/samples/async/async-receiver-responder)