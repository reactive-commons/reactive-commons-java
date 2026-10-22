package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.commons.exceptions.SendFailureNoAckException;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.commons.Headers.CORRELATION_ID;

/**
 * Every {@code requestReply} must release its reply registration in the {@link ReactiveReplyRouter}, whatever the
 * outcome: answered, timed out, publication failed, canceled by the caller or never subscribed. Otherwise, the
 * registrations accumulate in memory until the process is restarted.
 */
@ExtendWith(MockitoExtension.class)
class RabbitDirectAsyncGatewayReplyRouterTest {

    private static final Duration REPLY_TIMEOUT = Duration.ofMillis(300);

    private final MessageConverter converter =
            new RabbitJacksonMessageConverter(new DefaultObjectMapperSupplier().get());
    private final ReactiveReplyRouter router = new ReactiveReplyRouter();

    @Mock
    private ReactiveMessageSender sender;

    private RabbitDirectAsyncGateway gateway;

    @BeforeEach
    void init() {
        BrokerConfig config = new BrokerConfig(false, false, false, REPLY_TIMEOUT);
        gateway = new RabbitDirectAsyncGateway(config, router, sender, "exchange", converter,
                new SimpleMeterRegistry());
    }

    @Test
    void answeredQueryReleasesItsRegistration() throws Exception {
        when(sender.sendNoConfirm(any(), anyString(), anyString(), anyMap(), anyBoolean())).thenReturn(Mono.empty());

        StepVerifier.create(query())
                .then(() -> router.routeReply(sentCorrelationId(), converter.toMessage("answer")))
                .expectNext("answer")
                .verifyComplete();

        assertThat(pendingReplies()).isZero();
    }

    @Test
    void timedOutQueryReleasesItsRegistration() throws Exception {
        when(sender.sendNoConfirm(any(), anyString(), anyString(), anyMap(), anyBoolean())).thenReturn(Mono.empty());

        StepVerifier.create(query())
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(5));

        assertThat(pendingReplies()).isZero();
    }

    @Test
    void queryWhosePublicationFailsReleasesItsRegistration() throws Exception {
        when(sender.sendNoConfirm(any(), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(Mono.error(new SendFailureNoAckException("broker unavailable")));

        StepVerifier.create(query())
                .expectError(SendFailureNoAckException.class)
                .verify(Duration.ofSeconds(5));

        assertThat(pendingReplies()).as("no reply can ever arrive for a query that was not published").isZero();
    }

    @Test
    void queryCancelledByTheCallerReleasesItsRegistration() throws Exception {
        when(sender.sendNoConfirm(any(), anyString(), anyString(), anyMap(), anyBoolean())).thenReturn(Mono.empty());

        Disposable subscription = query().subscribe(v -> {
        }, e -> {
        });
        assertThat(pendingReplies()).isEqualTo(1);
        subscription.dispose(); // e.g. the caller has a shorter timeout or its HTTP request was cancelled

        assertThat(pendingReplies()).isZero();
    }

    @Test
    void queryThatIsNeverSubscribedRegistersNothing() throws Exception {
        query(); // assembled but never subscribed

        assertThat(pendingReplies()).isZero();
        verifyNoInteractions(sender);
    }

    private Mono<String> query() {
        return gateway.requestReply(new AsyncQuery<>("some.query", "data"), "some.target", String.class);
    }

    @SuppressWarnings("unchecked")
    private String sentCorrelationId() {
        ArgumentCaptor<Map<String, Object>> headers = ArgumentCaptor.forClass(Map.class);
        verify(sender).sendNoConfirm(any(), eq("exchange"), eq("some.target.query"), headers.capture(),
                anyBoolean());
        return headers.getValue().get(CORRELATION_ID).toString();
    }

    private int pendingReplies() throws Exception {
        Field processors = ReactiveReplyRouter.class.getDeclaredField("processors");
        processors.setAccessible(true);
        return ((Map<?, ?>) processors.get(router)).size();
    }
}
