package org.reactivecommons.async.rabbit.converters.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

public class CloudEventBuilderExtTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void asBytes(){
        Date date = new Date();
        SampleClass result = new SampleClass("35", "name1", date);
        byte[] arrayByte = CloudEventBuilderExt.asBytes(result);
        assertThat(arrayByte).isNotEmpty();
    }
}
