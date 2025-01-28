package org.reactivecommons.async.rabbit.converters.json;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.converters.json.CloudEventBuilderExt;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class CloudEventBuilderExtTest {

    @Test
    void asBytes() {
        Date date = new Date();
        SampleClass result = new SampleClass("35", "name1", date);
        byte[] arrayByte = CloudEventBuilderExt.asBytes(result);
        assertThat(arrayByte).isNotEmpty();
    }
}
