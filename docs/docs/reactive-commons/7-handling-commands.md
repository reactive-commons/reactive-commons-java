---
sidebar_position: 7
---

# Handling Commands

## HandlerRegistry configuration

To listen a Command you should register it in the HandlerRegistry and make it available as a Bean.

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

### Listening Raw Commands

If you need direct access to the raw message from RabbitMQ without domain model conversion, you can use `RawCommandHandler`.
Raw command handlers process all incoming commands without filtering by command name. This is useful when you need to handle
the message body, headers, or other low-level properties directly.

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(CommandsHandler commands) {
        return HandlerRegistry.register()
                .handleRawCommand(commands::handleRawCommandA);
    }
}
```

The handler implementation receives a `RawMessage` which can be cast to `RabbitMessage` to access the underlying message properties:

```java
@EnableCommandListeners
public class CommandsHandler {
    
    public Mono<Void> handleRawCommandA(RawMessage command) {
        RabbitMessage rawMessage = (RabbitMessage) command;
        System.out.println("RawEvent received: " + new String(rawMessage.getBody()));
        System.out.println("Content Type: " + rawMessage.getProperties().getContentType());
        System.out.println("Headers: " + rawMessage.getProperties().getHeaders());
        // Process the raw message
        return Mono.empty();
    }
}
```

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