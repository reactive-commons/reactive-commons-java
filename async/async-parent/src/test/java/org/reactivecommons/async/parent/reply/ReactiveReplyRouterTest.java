package org.reactivecommons.async.parent.reply;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.reactivecommons.async.parent.communications.Message;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;

public class ReactiveReplyRouterTest {

    private ReactiveReplyRouter replyRouter = new ReactiveReplyRouter();

    @Test
    public void shouldRouteReply(){
        final String uuid = UUID.randomUUID().toString();
        final Mono<Message> registered = replyRouter.register(uuid);

        Message message = Mockito.mock(Message.class);
        replyRouter.routeReply(uuid, message);

        StepVerifier.create(registered)
            .expectNext(message)
            .verifyComplete();

    }

    @Test
    public void shouldRouteEmptyResponse(){
        final String uuid = UUID.randomUUID().toString();
        final Mono<Message> registered = replyRouter.register(uuid);

        replyRouter.routeEmpty(uuid);

        StepVerifier.create(registered)
            .verifyComplete();
    }

    @Test
    public void shouldDeRegisterProcessor(){
        final String uuid = UUID.randomUUID().toString();
        final Mono<Message> registered = replyRouter.register(uuid);

        replyRouter.deregister(uuid);
        replyRouter.routeEmpty(uuid);

        StepVerifier.create(registered.timeout(Duration.ofSeconds(1)))
            .expectTimeout(Duration.ofSeconds(3)).verify();
    }

}