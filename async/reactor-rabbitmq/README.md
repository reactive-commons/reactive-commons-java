# Reactor RabbitMQ Module

This module is based on the source code from the archived official [reactor-rabbitmq](https://github.com/spring-attic/reactor-rabbitmq) library by VMware Inc. / Spring Attic.

## Original Library

- **Repository:** [https://github.com/spring-attic/reactor-rabbitmq](https://github.com/spring-attic/reactor-rabbitmq)
- **License:** [Apache License 2.0](https://github.com/spring-attic/reactor-rabbitmq/blob/main/LICENSE)

The original library was archived by its maintainers on September 26, 2025 and is no longer actively maintained. The source code was internalized into this project to allow continued use and maintenance as a dependency.

## Changes from Original

- Upgraded from Java 8/11 to Java 17
- Removed dead code and unused APIs
- `OutboundMessage` extended with optional `ackNotifier` callback, eliminating the need for a separate subclass
- Java 17 modernizations (records, sealed classes patterns, text blocks)
- SLF4J and SonarQube warning fixes
- Adapted for compatibility with the reactive-commons-java project
