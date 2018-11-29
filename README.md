# reactive-commons-java
The purpose of reactive-commons is to provide a set of abstractions and implementations over different patterns and practices that make the foundation of a reactive microservices architecture.

Even though the main purpose is to provide such abstractions in a mostly generic way such abstractions would be of little use without a concrete implementation so we provide some implementations in a best effors maner that aim to be easy to change, personalize and extend.

The first approach to this work was to release a very simple abstractions and a corresponding implementation over asyncronous message driven communication between microservices build on top of project-reactor and spring boot.

## Get Started
```groovy
    repositories {
      mavenCentral()
      maven { url "https://dl.bintray.com/reactive-commons/maven-artifacts" }
    }

    dependencies {
      compile 'org.reactivecommons:async-commons:0.0.1-alpha1'
    }
```

## Disclaimer
This Alpha version is a first development version intended for initial internal use with direct support from the developer of this module, so use with so much care.
