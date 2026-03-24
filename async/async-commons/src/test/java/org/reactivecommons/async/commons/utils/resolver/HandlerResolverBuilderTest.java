package org.reactivecommons.async.commons.utils.resolver;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.HandlerRegistry;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HandlerResolverBuilderTest {

    private final DefaultCommandHandler<Object> defaultHandler = command -> Mono.empty();

    @Test
    void buildResolverWithEmptyRegistries() {
        var registry = HandlerRegistry.register();
        var resolver = HandlerResolverBuilder.buildResolver("app", Map.of("r1", registry), defaultHandler);

        assertThat(resolver.hasQueryHandlers()).isFalse();
        assertThat(resolver.hasCommandHandlers()).isFalse();
        assertThat(resolver.hasNotificationListeners()).isFalse();
    }

    @Test
    void buildResolverWithQueryHandlers() {
        var registry = HandlerRegistry.register()
                .serveQuery("my.query", msg -> Mono.just("result"), String.class);

        var resolver = HandlerResolverBuilder.buildResolver("app", Map.of("r1", registry), defaultHandler);

        assertThat(resolver.hasQueryHandlers()).isTrue();
        assertThat(resolver.getQueryHandler("my.query")).isNotNull();
        assertThat(resolver.getQueryHandler("my.query").path()).isEqualTo("my.query");
    }

    @Test
    void buildResolverWithCommandHandlers() {
        var registry = HandlerRegistry.register()
                .handleCommand("my.cmd", cmd -> Mono.empty(), String.class);

        var resolver = HandlerResolverBuilder.buildResolver("app", Map.of("r1", registry), defaultHandler);

        assertThat(resolver.hasCommandHandlers()).isTrue();
        assertThat(resolver.getCommandHandler("my.cmd")).isNotNull();
        assertThat(resolver.getCommandHandler("my.cmd").path()).isEqualTo("my.cmd");
    }

    @Test
    void buildResolverWithEventListeners() {
        var registry = HandlerRegistry.register()
                .listenEvent("my.event", evt -> Mono.empty(), String.class);

        var resolver = HandlerResolverBuilder.buildResolver("app", Map.of("r1", registry), defaultHandler);

        assertThat(resolver.getEventListener("my.event")).isNotNull();
    }

    @Test
    void buildResolverWithNotificationListeners() {
        var registry = HandlerRegistry.register()
                .listenNotificationEvent("notif.event", evt -> Mono.empty(), String.class);

        var resolver = HandlerResolverBuilder.buildResolver("app", Map.of("r1", registry), defaultHandler);

        assertThat(resolver.hasNotificationListeners()).isTrue();
        assertThat(resolver.getNotificationListener("notif.event")).isNotNull();
    }

    @Test
    void buildResolverWithQueueListeners() {
        var registry = HandlerRegistry.register()
                .listenQueue("myQueue", msg -> Mono.empty());

        var resolver = HandlerResolverBuilder.buildResolver("app", Map.of("r1", registry), defaultHandler);

        assertThat(resolver.getQueueListeners()).containsKey("myQueue");
    }

    @Test
    void buildResolverWithDynamicEventHandlers() {
        var registry = HandlerRegistry.register()
                .handleDynamicEvents("event.*", evt -> Mono.empty(), String.class);

        var resolver = HandlerResolverBuilder.buildResolver("app", Map.of("r1", registry), defaultHandler);

        assertThat(resolver.getEventListener("event.foo")).isNotNull();
    }

    @Test
    void defaultCommandHandlerReturnedForUnknownPath() {
        var registry = HandlerRegistry.register();
        var resolver = HandlerResolverBuilder.buildResolver("app", Map.of("r1", registry), defaultHandler);

        var handler = resolver.getCommandHandler("unknown.command");
        assertThat(handler).isNotNull();
        assertThat(handler.path()).isEmpty();
    }

    @Test
    void buildResolverWithMultipleRegistries() {
        var registry1 = HandlerRegistry.register()
                .serveQuery("q1", msg -> Mono.just("r1"), String.class);
        var registry2 = HandlerRegistry.register()
                .handleCommand("c1", cmd -> Mono.empty(), String.class);

        var resolver = HandlerResolverBuilder.buildResolver("app",
                Map.of("r1", registry1, "r2", registry2), defaultHandler);

        assertThat(resolver.hasQueryHandlers()).isTrue();
        assertThat(resolver.hasCommandHandlers()).isTrue();
    }

    @Test
    void buildResolverForCustomDomain() {
        var registry = HandlerRegistry.register()
                .handleCommand("custom", "domain.cmd", cmd -> Mono.empty(), String.class);

        var resolver = HandlerResolverBuilder.buildResolver("custom", Map.of("r1", registry), defaultHandler);

        assertThat(resolver.hasCommandHandlers()).isTrue();
        assertThat(resolver.getCommandHandler("domain.cmd")).isNotNull();
    }

    @Test
    void buildResolverForDomainIgnoresOtherDomainEntries() {
        var registry = HandlerRegistry.register()
                .handleCommand("other", "other.cmd", cmd -> Mono.empty(), String.class);

        var resolver = HandlerResolverBuilder.buildResolver("app", Map.of("r1", registry), defaultHandler);

        assertThat(resolver.hasCommandHandlers()).isFalse();
    }

    @Test
    void buildResolverEventListenersIncludesDynamics() {
        var registry = HandlerRegistry.register()
                .listenEvent("static.event", evt -> Mono.empty(), String.class)
                .handleDynamicEvents("dynamic.*", evt -> Mono.empty(), String.class);

        var resolver = HandlerResolverBuilder.buildResolver("app", Map.of("r1", registry), defaultHandler);

        assertThat(resolver.getEventListener("static.event")).isNotNull();
        assertThat(resolver.getEventListener("dynamic.foo")).isNotNull();
    }
}
