package org.reactivecommons.async.api;


import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;

import static org.assertj.core.api.Assertions.assertThat;

class MessageConversionExceptionTest {

    @Test
    void shouldConstructProperly(){
        String message = "some message";
        Throwable cause = new RuntimeException();

        final MessageConversionException exception = new MessageConversionException(message, cause);

        assertThat(exception).hasMessage(message).hasCause(cause);
    }

}