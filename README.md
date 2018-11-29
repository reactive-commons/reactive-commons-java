# reactive-commons-java
The purpose of reactive-commons is to provide a set of abstractions and implementations over different patterns and practices that make the foundation of a reactive microservices architecture.

Even though the main purpose is to provide such abstractions in a mostly generic way such abstractions would be of little use without a concrete implementation so we provide some implementations in a best effors maner that aim to be easy to change, personalize and extend.

The first approach to this work was to release a very simple abstractions and a corresponding implementation over asyncronous message driven communication between microservices build on top of project-reactor and spring boot.

## Get Started
To include all (API and implementation):
```groovy
    repositories {
      mavenCentral()
      maven { url "https://dl.bintray.com/reactive-commons/maven-artifacts" }
    }

    dependencies {
      compile 'org.reactivecommons:async-commons:0.0.1-alpha1'
    }
```

To include only domain events API:

```groovy
    dependencies {
      compile 'org.reactivecommons:domain-events-api:0.0.1-alpha1'
    }
```

To include only async commons API:

```groovy
    dependencies {
      compile 'org.reactivecommons:async-commons-api:0.0.1-alpha1'
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


## Disclaimer
This Alpha version is a first development version intended for initial internal use with direct support from the developer of this module, so use with so much care.
