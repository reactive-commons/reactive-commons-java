![](https://github.com/reactive-commons/reactive-commons-java/workflows/reactive-commons-ci-cd/badge.svg)
[![Reactor RabbitMQ](https://maven-badges.herokuapp.com/maven-central/org.reactivecommons/async-commons-starter/badge.svg)](https://mvnrepository.com/artifact/org.reactivecommons/async-commons-starter)
# reactive-commons-java
The purpose of reactive-commons is to provide a set of abstractions and implementations over different patterns and practices that make the foundation of a reactive microservices architecture.

Even though the main purpose is to provide such abstractions in a mostly generic way such abstractions would be of little use without a concrete implementation so we provide some implementations in a best effors maner that aim to be easy to change, personalize and extend.

The first approach to this work was to release a very simple abstractions and a corresponding implementation over asyncronous message driven communication between microservices build on top of project-reactor and spring boot.

## Get Started
To include all (API and implementation) (Spring boot Starter):
```groovy

    dependencies {
      compile 'org.reactivecommons:async-commons-starter:0.6.1-beta'
    }

    //IMPORTANT! if you use the version 0.6.x
    repositories {
	    ...
	    maven { url "https://repo.spring.io/milestone" }
    }
    configurations.all {
        resolutionStrategy.eachDependency {DependencyResolveDetails details ->
            if (details.requested.group == 'io.projectreactor.rabbitmq'){
                details.useVersion('1.5.0-M2')
                details.because('Upgrade')
            }
        }
    }

```

In application.properties
```
spring.application.name=MyAppName
```

Or yaml

```
spring:
  application:
    name: myAppName
```

To include only domain events API:

```groovy
    dependencies {
      compile 'org.reactivecommons:domain-events-api:0.6.1-beta'
    }
```

To include only async commons API:

```groovy
    dependencies {
      compile 'org.reactivecommons:async-commons-api:0.6.1-beta'
    }
```

## Main abstractions

### Domain events API (Broadcast of events)

```java
package org.reactivecommons.api.domain;
import org.reactivestreams.Publisher;

public interface DomainEventBus {
    <T> Publisher<Void> emit(DomainEvent<T> event);
}
```

The above interface is the main interface for Broadcast of domain events, the DomainEvent class has the following structure:

```java
package org.reactivecommons.api.domain;

public class DomainEvent<T> {
    private final String name;
    private final String eventId;
    private final T data;

    public DomainEvent(String name, String eventId, T data) {
        this.name = name;
        this.eventId = eventId;
        this.data = data;
    }

    //... getters, equals, hascode, toString impl..

}
```

Usage example:

```java
public class ManageTasksUseCase {

    private TaskToDoRepository tasks;
    private DomainEventBus eventBus;

    public Mono<TaskToDo> createNew(String name, String description) {
        return uuid()
            .flatMap(id -> TaskToDoFactory.createTask(id, name, description))
            .flatMap(tasks::save)
            .flatMap(task -> emitCreatedEvent(task).thenReturn(task));
    }

    private Mono<Void> emitCreatedEvent(TaskToDo task) {
        return Mono.from(eventBus.emit(new DomainEvent<>("task.created", task.getId(), task)));
    }
    //...
}
```

Then enable this feature in a Configuration class and inject implementation:

```java
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableDomainEventBus
public class MainApplication {
    public static void main(String[] args) {
        SpringApplication.run(MainApplication.class, args);
    }
    
    @Bean
    public ManageTasksUseCase manageTasksUseCase(TaskToDoRepository tasks, DomainEventBus eventBus) {
        return new ManageTasksUseCase(tasks, eventBus);
    }    
    
}


```

Don't forget to add the starter bundle to the main spring boot module (application):

```groovy
    dependencies {
      compile 'org.reactivecommons:async-commons-starter:0.6.1-beta'
    }
```


Or add the implementation dependency if for any reason you don't want to use the starter:

```groovy
    dependencies {
      compile 'org.reactivecommons:async-commons:0.6.1-beta'
    }
```


### Domain Event-Listener
Reactive commons has four types of listeners implemented, available to be registered in the application via the **HandlerRegistry**, each of them is designed to tackle   
common requirements for listeners in event based applications and abstracts the behavior of event flow in every situation (Varying for example in retrying strategy, dead letter events, sources and so on).

The available event listeners are:
- Domain Event Listener
- Query Event Listener
- Command Listener
- Notification Listener

Example Code:
```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;

@Configuration
public class SomeConfigurationClass {

    @Autowired
    private ManageTasksUseCase someBusinessDependency;

    @Bean
    public HandlerRegistry notificationEvents() {
        return HandlerRegistry.register()
            .listenNotificationEvent("some.event.name", event -> someBusinessDependency.someFunctionReturningMonoVoid(event), SomeClass.class)
            .listenEvent("some.event.name2", event -> someBusinessDependency.functionReturningMonoVoid(event), Some.class)    
            .serveQuery("query.name", query -> someBusinessDependency.findSomething(query), SomeQuery.class)    
            .handleCommand("command.name", cmd -> someBusinessDependency.handleCommand(cmd), CmdClass.class);    
    }
}
```

The first line below "HandlerRegistry.register()"  shows how to handle a notification event (Notification event: an event that should be handled by
every running instance of a microservice, e.g: notify to every instance that a configuration setting has changed
  and has to do a hot reload from a persistent source of that data).
  
The line ".listenEvent.." shows how to handle a standard event, and event that should be handled only once by some running instance of
the microservice.

The line ".serveQuery..." shows how to handle a standard request/reply or rpc messages flow.

The line ".handleCommand..." shows how to handle a standard directed command, a message with a delivery guarantee.

### Request-Reply
Example Code:

```java
    public Mono<AggregateResponse> queryInformation() {
        AsyncQuery<String> userQuery = new AsyncQuery<>("user.byId", "42");
        AsyncQuery<String> projectQuery = new AsyncQuery<>("project.byId", "343");
        AsyncQuery<String> productQuery = new AsyncQuery<>("product.byId", "22");

        Mono<User> user = gateway.requestReply(userQuery, "Users", User.class);
        Mono<Project> project = gateway.requestReply(projectQuery, "Projects", Project.class);
        Mono<Product> product = gateway.requestReply(productQuery, "Products", Product.class);

        return zip(user, project, product).map(function(this::aggregate));
    }
```

### Direct Commands

```java
    package org.reactivecommons.async.api;

    import org.reactivecommons.api.domain.Command;
    import reactor.core.publisher.Mono;

    public interface DirectAsyncGateway {
        <T> Mono<Void> sendCommand(Command<T> command, String targetName);
        <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type);
    }
```

#### Command Type

```java
    package org.reactivecommons.api.domain;

    public class Command<T> {
        private final String name;
        private final String commandId;
        private final T data;
    }
```

#### Send Commands


```java    
    private static final String REGISTER_MEMBER = "Members.registerMember";
    private static final String TARGET = "Members";
    private DirectAsyncGateway asyncGateway;
    
    public Mono<Void> registerMember(Member member){
        String uuid = UUID.randomUUID().toString();
        return asyncGateway.sendCommand(new Command<>(REGISTER_MEMBER, uuid, member), TARGET);
    }
```    

#### Handle Commands
```java
    private static final String REGISTER_MEMBER = "Members.registerMember";

    @Bean
    public HandlerRegistry commandHandlers(MembersRegistryUseCase useCase) {
        return HandlerRegistry.register()
            .handleCommand(REGISTER_MEMBER, useCase::registerMember, Member.class);
    }
``` 

### Broker Configuration (RabbitMQ)


```
spring.rabbitmq.host= 8.8.8.1
spring.rabbitmq.port=5729
spring.rabbitmq.username=user
spring.rabbitmq.password=pass

```

### Retry Strategy Config (RabbitMQ)

```
app.async.withDLQRetry=true
app.async.retryDelay=1000
app.async.maxRetries=10

```
### Domain custom Configuration (RabbitMQ)


```
app.async.domain.events.exchange=exchangeCustomName
app.async.domain.events.maxLengthBytes=125000000

```

### Direct custom Configuration (RabbitMQ)


```
app.async.direct.exchange=exchangeCustomName
app.async.direct.maxLengthBytes=125000000
```

### Global custom Configuration (RabbitMQ)


```
app.async.global.exchange=exchangeCustomName
app.async.global.maxLengthBytes=125000000
```

* withDLQRetry: Whether to enable or not the new Retry DLQ Strategy
* retryDelay: Delay retry value in ms
* maxRetries: Number of retries in case of error in addition to the one automatic retry per queue.

