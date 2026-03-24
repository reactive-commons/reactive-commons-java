package org.reactivecommons.async.commons.exceptions;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SendFailureNoAckExceptionTest {

    @Test
    void messageIsSet() {
        var ex = new SendFailureNoAckException("no ack");
        assertThat(ex.getMessage()).isEqualTo("no ack");
    }
}
