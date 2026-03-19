package org.reactivecommons.async.commons.converters.json;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultObjectMapperSupplierTest {

    private final DefaultObjectMapperSupplier defaultObjectMapperSupplier = new DefaultObjectMapperSupplier();

    @Test
    void shouldMapWithUnknownProperties() {
        JsonMapper jsonMapper = defaultObjectMapperSupplier.get();

        SampleClassExtra base = new SampleClassExtra("23", "one", new Date(), 45L);
        final String serialized = jsonMapper.writeValueAsString(base);

        final SampleClass result = jsonMapper.readValue(serialized, SampleClass.class);

        assertThat(result).usingRecursiveComparison().isEqualTo(base);
    }

    @Test
    void shouldSerializeAndDeserializeLocalDateTime() {
        JsonMapper jsonMapper = defaultObjectMapperSupplier.get();

        LocalDateTime now = LocalDateTime.of(2025, 12, 10, 14, 30, 0);
        SampleClassWithLocalDateTime sample = new SampleClassWithLocalDateTime("123", "Test", now);

        final String serialized = jsonMapper.writeValueAsString(sample);
        assertThat(serialized).contains("2025-12-10");

        final SampleClassWithLocalDateTime result = jsonMapper.readValue(serialized, SampleClassWithLocalDateTime.class);

        assertThat(result.getId()).isEqualTo("123");
        assertThat(result.getName()).isEqualTo("Test");
        assertThat(result.getDateSend()).isEqualTo(now);
    }

    @Getter
    private static class SampleClassExtra extends SampleClass {

        public SampleClassExtra(String id, String name, Date date, Long newProp) {
            super(id, name, date);
            this.newProp = newProp;
        }

        private final Long newProp;
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    private static class SampleClassWithLocalDateTime {
        private String id;
        private String name;
        private LocalDateTime dateSend;
    }

}
