![CI/CD](https://github.com/reactive-commons/reactive-commons-java/workflows/reactive-commons-ci-cd/badge.svg)
[![Maven Central](https://img.shields.io/maven-central/v/org.reactivecommons/async-commons-rabbit-starter)](https://central.sonatype.com/artifact/org.reactivecommons/async-commons-rabbit-starter)

# reactive-commons-java

The purpose of reactive-commons is to provide a set of abstractions and implementations over different patterns and practices that make the foundation of a reactive microservices architecture.

Even though the main purpose is to provide such abstractions in a mostly generic way, they would be of little use without a concrete implementation. So we provide implementations in a best-effort manner that aim to be easy to change, personalize, and extend.

The first approach to this work was to release simple abstractions and a corresponding implementation over asynchronous message-driven communication between microservices, built on top of Project Reactor and Spring Boot.

---

## Documentation

**Full documentation is available at:**

> ### 👉 [https://bancolombia.github.io/reactive-commons-java/](https://bancolombia.github.io/reactive-commons-java)

---

## Related

> - **Other projects:** [https://github.com/bancolombia](https://github.com/bancolombia)
> - **Sponsored by:** [Bancolombia Tech](https://medium.com/bancolombia-tech)

---

## Third-Party Code Credits

This project includes source code internalized from the following open-source libraries:

### reactor-rabbitmq
- **Repository:** [https://github.com/spring-attic/reactor-rabbitmq](https://github.com/spring-attic/reactor-rabbitmq)
- **License:** [Apache License 2.0](https://github.com/spring-attic/reactor-rabbitmq/blob/main/LICENSE)

### CloudEvents JSON Jackson
- **Repository:** [https://github.com/cloudevents/sdk-java](https://github.com/cloudevents/sdk-java)
- **License:** [Apache License 2.0](https://github.com/cloudevents/sdk-java/blob/main/LICENSE)
