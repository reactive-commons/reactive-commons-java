package org.reactivecommons.async.commons.reply;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.reactivecommons.async.commons.communications.Message;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReactiveReplyRouterTest {

    private final ReactiveReplyRouter replyRouter = new ReactiveReplyRouter();

    @Test
    void shouldRouteReply() {
        final String uuid = UUID.randomUUID().toString();
        final Mono<Message> registered = replyRouter.register(uuid);

        Message message = Mockito.mock(Message.class);
        replyRouter.routeReply(uuid, message);

        StepVerifier.create(registered)
            .expectNext(message)
            .verifyComplete();

    }

    @Test
    void shouldRouteEmptyResponse() {
        final String uuid = UUID.randomUUID().toString();
        final Mono<Message> registered = replyRouter.register(uuid);

        replyRouter.routeEmpty(uuid);

        StepVerifier.create(registered)
                .verifyComplete();
    }

    @Test
    void failAllFailsEveryPendingCorrelationAndReportsTheCount() {
        final Mono<Message> first = replyRouter.register(UUID.randomUUID().toString());
        final Mono<Message> second = replyRouter.register(UUID.randomUUID().toString());
        RuntimeException cause = new RuntimeException("reconnect");

        int lost = replyRouter.failAll(cause);

        assertThat(lost).isEqualTo(2);
        StepVerifier.create(first).expectErrorMessage("reconnect").verify();
        StepVerifier.create(second).expectErrorMessage("reconnect").verify();
    }

    @Test
    void failAllOnAnEmptyRouterReportsZero() {
        assertThat(replyRouter.failAll(new RuntimeException("x"))).isZero();
    }

    @Test
    void shouldDeRegisterProcessor() {
        final String uuid = UUID.randomUUID().toString();
        final Mono<Message> registered = replyRouter.register(uuid);

        replyRouter.deregister(uuid);
        replyRouter.routeEmpty(uuid);

        StepVerifier.create(registered.timeout(Duration.ofSeconds(1)))
                .expectTimeout(Duration.ofSeconds(3)).verify();
    }

}
