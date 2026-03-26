package reactor.rabbitmq;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.FluxSink;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsumeOptionsTest {

    @Test
    void defaultValues() {
        var opts = new ConsumeOptions();
        assertThat(opts.getQos()).isEqualTo(250);
        assertThat(opts.getConsumerTag()).isEmpty();
        assertThat(opts.getOverflowStrategy()).isEqualTo(FluxSink.OverflowStrategy.BUFFER);
        assertThat(opts.getHookBeforeEmitBiFunction()).isNotNull();
        assertThat(opts.getStopConsumingBiFunction()).isNotNull();
        assertThat(opts.getExceptionHandler()).isNotNull();
        assertThat(opts.getChannelCallback()).isNotNull();
        assertThat(opts.getArguments()).isEmpty();
    }

    @Test
    void hookBeforeEmitDefaultReturnsTrue() {
        var opts = new ConsumeOptions();
        assertThat(opts.getHookBeforeEmitBiFunction().test(1L, null)).isTrue();
    }

    @Test
    void stopConsumingDefaultReturnsFalse() {
        var opts = new ConsumeOptions();
        assertThat(opts.getStopConsumingBiFunction().test(1L, null)).isFalse();
    }

    @Test
    void qosRejectsNegative() {
        var opts = new ConsumeOptions();
        assertThatThrownBy(() -> opts.qos(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void qosAcceptsZero() {
        var opts = new ConsumeOptions().qos(0);
        assertThat(opts.getQos()).isZero();
    }

    @Test
    void consumerTagRejectsNull() {
        var opts = new ConsumeOptions();
        assertThatThrownBy(() -> opts.consumerTag(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fluentSetters() {
        Map<String, Object> args = Map.of("key", "value");
        var opts = new ConsumeOptions()
                .qos(100)
                .consumerTag("tag")
                .overflowStrategy(FluxSink.OverflowStrategy.DROP)
                .arguments(args);

        assertThat(opts.getQos()).isEqualTo(100);
        assertThat(opts.getConsumerTag()).isEqualTo("tag");
        assertThat(opts.getOverflowStrategy()).isEqualTo(FluxSink.OverflowStrategy.DROP);
        assertThat(opts.getArguments()).isEqualTo(args);
    }
}
