package org.reactivecommons.async.kafka;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.reactivecommons.async.impl.config.annotations.EnableTopicListeners;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.reactivecommons.async.kafka.KafkaScenarioSupport.startOnOwnDomain;

/**
 * KC-12: the application starts when its broker cannot be reached (as {@code spring.kafka.admin.fail-fast=false} does
 * in Spring Boot), instead of failing after the 60 s of listing the topics of the cluster; the listeners keep trying,
 * and the health check reports the domain {@code DOWN} within its timeout.
 */
class UnreachableBrokerStartupTest {

    private static final String DOMAIN = "accounts";

    @Configuration
    @EnableTopicListeners
    @EnableDomainEventBus
    static class ListenAndSend {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenTopic(DOMAIN, "unreachable.topic", message -> Mono.empty());
        }
    }

    @Test
    void theContextStartsWithinSecondsWhenTheBrokerCannotBeReached() {
        // Nothing listens on port 1: every connection is refused. Topics are checked (the default)
        AnnotationConfigApplicationContext context = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> startOnOwnDomain("localhost:1", "unreachable-broker", DOMAIN, Map.of(), null,
                        ListenAndSend.class));
        try {
            assertThat(context.isActive()).isTrue();
            StepVerifier.create(context.getBean(ConnectionManager.class).getProviders().get(DOMAIN).healthCheck())
                    .assertNext(health -> assertThat(((RCHealth) health).status()).isEqualTo(RCHealth.Status.DOWN))
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
        } finally {
            context.close();
        }
    }
}
