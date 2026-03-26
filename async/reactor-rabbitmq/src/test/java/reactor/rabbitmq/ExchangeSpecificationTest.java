package reactor.rabbitmq;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeSpecificationTest {

    @Test
    void defaultValues() {
        var spec = ExchangeSpecification.exchange();
        assertThat(spec.getName()).isNull();
        assertThat(spec.getType()).isEqualTo("direct");
        assertThat(spec.isDurable()).isFalse();
        assertThat(spec.isAutoDelete()).isFalse();
        assertThat(spec.isInternal()).isFalse();
        assertThat(spec.isPassive()).isFalse();
        assertThat(spec.getArguments()).isNull();
    }

    @Test
    void factoryWithName() {
        var spec = ExchangeSpecification.exchange("my-exchange");
        assertThat(spec.getName()).isEqualTo("my-exchange");
        assertThat(spec.getType()).isEqualTo("direct");
    }

    @Test
    void fluentSetters() {
        Map<String, Object> args = Map.of("x-expires", 60000);
        var spec = ExchangeSpecification.exchange()
                .name("test")
                .type("fanout")
                .durable(true)
                .autoDelete(true)
                .internal(true)
                .passive(true)
                .arguments(args);

        assertThat(spec.getName()).isEqualTo("test");
        assertThat(spec.getType()).isEqualTo("fanout");
        assertThat(spec.isDurable()).isTrue();
        assertThat(spec.isAutoDelete()).isTrue();
        assertThat(spec.isInternal()).isTrue();
        assertThat(spec.isPassive()).isTrue();
        assertThat(spec.getArguments()).isEqualTo(args);
    }
}
