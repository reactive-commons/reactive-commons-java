package org.reactivecommons.async.commons.converters.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

public class DefaultObjectMapperSupplierTest {

    private final DefaultObjectMapperSupplier defaultObjectMapperSupplier = new DefaultObjectMapperSupplier();


    @Test
    public void shouldMapWithUnknownProperties() throws IOException {
        ObjectMapper objectMapper = defaultObjectMapperSupplier.get();

        SampleClassExtra base = new SampleClassExtra("23", "one", new Date(), 45l);
        final String serialized = objectMapper.writeValueAsString(base);

        final SampleClass result = objectMapper.readValue(serialized, SampleClass.class);

        assertThat(result).isEqualToComparingFieldByField(base);
    }

    @Getter
    private static class SampleClassExtra extends SampleClass {

        public SampleClassExtra(String id, String name, Date date, Long newProp) {
            super(id, name, date);
            this.newProp = newProp;
        }

        private final Long newProp;
    }

}
