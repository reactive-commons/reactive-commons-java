---
sidebar_position: 7
---

# Handling Commands

## HandlerRegistry configuration

To listen a Command you should register it in the HandlerRegistry and make it available as a Bean

### Listening Commands

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(CommandsHandler commands) {
        return HandlerRegistry.register()
                .handleCommand("some.command.name", commands::handleCommandA, Object.class/*change for proper model*/);
    }
}
```

To effectively start listening commands you should add the annotation `@EnableCommandListeners` to your MainApplication class or any other spring Configuration class, for example the `CommandsHandler` class can be like:

```java
@EnableCommandListeners
public class CommandsHandler {
    public Mono<Void> handleCommandA(Command<Object/*change for proper model*/> command) {
        System.out.println("command received: " + command.getName() + " ->" + command.getData());
        return Mono.empty();
    }
}
```

As the model of commands is direct, a consumer always can send commands to the service provider, by this reason you may receive commands that you don`t have configured.

### Wildcards

You may need to handle variable command names that have the same structure, in that case you can specfy a pattern with '*' wildcard, for example:

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(CommandsHandler commands) {
        return HandlerRegistry.register()
                .handleCommand("send.*.notification", commands::handleCommandA, Object.class/*change for proper model*/);
    }
}
```

So any consumer can send a command with a name that matches with pattern, for example: `send.email.notification`

## Example

You can see a real example at [samples/async/async-receiver-responder](https://github.com/reactive-commons/reactive-commons-java/tree/master/samples/async/async-receiver-responder)