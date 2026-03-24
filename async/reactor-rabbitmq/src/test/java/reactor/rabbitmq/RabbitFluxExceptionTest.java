package reactor.rabbitmq;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitFluxExceptionTest {

    @Test
    void noArgConstructor() {
        var ex = new RabbitFluxException();
        assertThat(ex.getMessage()).isNull();
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void messageConstructor() {
        var ex = new RabbitFluxException("error");
        assertThat(ex.getMessage()).isEqualTo("error");
    }

    @Test
    void messageCauseConstructor() {
        var cause = new RuntimeException("root");
        var ex = new RabbitFluxException("error", cause);
        assertThat(ex.getMessage()).isEqualTo("error");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void causeConstructor() {
        var cause = new RuntimeException("root");
        var ex = new RabbitFluxException(cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void retryTimeoutExceptionExtends() {
        var cause = new RuntimeException("timeout");
        var ex = new RabbitFluxRetryTimeoutException("timed out", cause);
        assertThat(ex).isInstanceOf(RabbitFluxException.class);
        assertThat(ex.getMessage()).isEqualTo("timed out");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
