package org.reactivecommons.async.commons.exceptions;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageConversionExceptionTest {

    @Test
    void messageAndCause() {
        var cause = new RuntimeException("root");
        var ex = new MessageConversionException("fail", cause);
        assertThat(ex.getMessage()).isEqualTo("fail");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void messageOnly() {
        var ex = new MessageConversionException("fail");
        assertThat(ex.getMessage()).isEqualTo("fail");
    }

    @Test
    void wrapsException() {
        var cause = new Exception("root");
        var ex = new MessageConversionException(cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
