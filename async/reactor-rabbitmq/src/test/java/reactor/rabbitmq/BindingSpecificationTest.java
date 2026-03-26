package reactor.rabbitmq;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BindingSpecificationTest {

    @Test
    void defaultValues() {
        var spec = BindingSpecification.binding();
        assertThat(spec.getQueue()).isNull();
        assertThat(spec.getExchange()).isNull();
        assertThat(spec.getExchangeTo()).isNull();
        assertThat(spec.getRoutingKey()).isNull();
        assertThat(spec.getArguments()).isNull();
    }

    @Test
    void queueBindingFactory() {
        var spec = BindingSpecification.binding("my-exchange", "my-rk", "my-queue");
        assertThat(spec.getExchange()).isEqualTo("my-exchange");
        assertThat(spec.getRoutingKey()).isEqualTo("my-rk");
        assertThat(spec.getQueue()).isEqualTo("my-queue");
    }

    @Test
    void queueBindingStaticAlias() {
        var spec = BindingSpecification.queueBinding("exch", "rk", "q");
        assertThat(spec.getExchange()).isEqualTo("exch");
        assertThat(spec.getRoutingKey()).isEqualTo("rk");
        assertThat(spec.getQueue()).isEqualTo("q");
    }

    @Test
    void exchangeBindingFactory() {
        var spec = BindingSpecification.exchangeBinding("from-exch", "rk", "to-exch");
        assertThat(spec.getExchange()).isEqualTo("from-exch");
        assertThat(spec.getRoutingKey()).isEqualTo("rk");
        assertThat(spec.getExchangeTo()).isEqualTo("to-exch");
        assertThat(spec.getQueue()).isNull();
    }

    @Test
    void fluentSetters() {
        Map<String, Object> args = Map.of("x-match", "all");
        var spec = BindingSpecification.binding()
                .queue("q")
                .exchange("e")
                .exchangeTo("e2")
                .routingKey("rk")
                .arguments(args);

        assertThat(spec.getQueue()).isEqualTo("q");
        assertThat(spec.getExchange()).isEqualTo("e");
        assertThat(spec.getExchangeTo()).isEqualTo("e2");
        assertThat(spec.getRoutingKey()).isEqualTo("rk");
        assertThat(spec.getArguments()).isEqualTo(args);
    }

    @Test
    void exchangeFromSetsExchange() {
        var spec = BindingSpecification.binding().exchangeFrom("src-exch");
        assertThat(spec.getExchange()).isEqualTo("src-exch");
    }
}
