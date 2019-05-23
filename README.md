[![Build Status](https://travis-ci.org/reactive-commons/reactive-commons-java.svg?branch=master)](https://travis-ci.org/reactive-commons/reactive-commons-java)
# reactive-commons-java
The purpose of reactive-commons is to provide a set of abstractions and implementations over different patterns and practices that make the foundation of a reactive microservices architecture.

Even though the main purpose is to provide such abstractions in a mostly generic way such abstractions would be of little use without a concrete implementation so we provide some implementations in a best effors maner that aim to be easy to change, personalize and extend.

The first approach to this work was to release a very simple abstractions and a corresponding implementation over asyncronous message driven communication between microservices build on top of project-reactor and spring boot.

## Get Started
To include all (API and implementation) (Spring boot Starter):
```groovy

    dependencies {
      compile 'org.reactivecommons:async-commons-starter:0.0.7-beta1'
    }
```

To include only domain events API:

```groovy
    dependencies {
      compile 'org.reactivecommons:domain-events-api:0.0.7-beta1'
    }
```

To include only async commons API:

```groovy
    dependencies {
      compile 'org.reactivecommons:async-commons-api:0.0.7-beta1'
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

    public String getName() {
        return this.name;
    }

    public String getEventId() {
        return this.eventId;
    }

    public T getData() {
        return this.data;
    }

    //... equals, hascode, toString impl..

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
Don't forget to add the implementation dependency to the main spring boot module:

```groovy
    dependencies {
      compile 'org.reactivecommons:async-commons:0.0.1-alpha1'
    }
```

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
