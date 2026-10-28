package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.AMQP;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.exceptions.SendConfirmUnknownException;
import org.reactivecommons.async.commons.exceptions.SendFailureNoAckException;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ConfirmBatchTest {

    @Test
    void aBatchFailsWithItsFirstFailureAndTellsWhichMessagesFailed() {
        ConfirmBatch batch = new ConfirmBatch(Duration.ofSeconds(10));
        batch.sourceSubscribed();
        OutboundMessage acked = batch.published(message("m1"));
        OutboundMessage nacked = batch.published(message("m2"));
        OutboundMessage returned = batch.published(message("m3"));

        batch.settled(new OutboundMessageResult<>(acked, true));
        batch.settled(new OutboundMessageResult<>(nacked, false));
        batch.settled(new OutboundMessageResult<>(returned, true, true));

        StepVerifier.create(batch.outcome())
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(SendFailureNoAckException.class)
                        .hasMessage("2 of the 3 messages taken from the batch failed: m2 (nack), m3 (returned)"))
                .verify();
    }

    @Test
    void aBatchWithoutFailuresCompletes() {
        ConfirmBatch batch = new ConfirmBatch(Duration.ofSeconds(10));
        batch.sourceSubscribed();
        batch.settled(new OutboundMessageResult<>(batch.published(message("m1")), true));

        assertThat(batch.expired()).isNull();
        StepVerifier.create(batch.outcome()).verifyComplete();
    }

    @Test
    void onlyTheFirstFailuresAreListed() {
        ConfirmBatch batch = new ConfirmBatch(Duration.ofSeconds(10));
        batch.sourceSubscribed();
        int messages = ConfirmBatch.MAX_LISTED_FAILURES + 50;
        for (int i = 0; i < messages; i++) {
            batch.settled(new OutboundMessageResult<>(batch.published(message("m" + i)), false));
        }

        StepVerifier.create(batch.outcome())
                .expectErrorSatisfies(error -> assertThat(error.getMessage())
                        .startsWith(messages + " of the " + messages + " messages taken from the batch failed: m0 ")
                        .contains("m99 (nack)")
                        .doesNotContain("m100 ")
                        .endsWith(" and 50 more"))
                .verify();
    }

    @Test
    void aMessageNotConfirmedWithinTheSendTimeoutExpiresTheBatch() {
        ConfirmBatch batch = new ConfirmBatch(Duration.ofMillis(50));
        batch.sourceSubscribed();
        batch.settled(new OutboundMessageResult<>(batch.published(message("m1")), true));
        batch.published(message("m2"));
        assertThat(batch.expired()).as("before the send timeout").isNull();

        // expired() records the failures of the batch: read once
        Throwable expired = await().atMost(Duration.ofSeconds(5)).until(batch::expired, Objects::nonNull);

        assertThat(expired)
                .isInstanceOf(SendConfirmUnknownException.class)
                .hasMessageContaining("1 of the 2 messages taken from the batch failed: m2 (in doubt)");
    }

    @Test
    void aBatchThatCannotStartPublishingExpiresAfterTheSendTimeout() {
        ConfirmBatch batch = new ConfirmBatch(Duration.ofMillis(50));
        assertThat(batch.expired()).as("before the send timeout").isNull();

        Throwable expired = await().atMost(Duration.ofSeconds(5)).until(batch::expired, Objects::nonNull);

        assertThat(expired)
                .isInstanceOf(SendConfirmUnknownException.class)
                .hasMessageContaining("could not start publishing");
    }

    private static OutboundMessage message(String id) {
        return new OutboundMessage("exchange", "rkey", new AMQP.BasicProperties.Builder().messageId(id).build(),
                new byte[0]);
    }
}
