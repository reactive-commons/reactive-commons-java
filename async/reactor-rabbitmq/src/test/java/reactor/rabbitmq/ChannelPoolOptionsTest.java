package reactor.rabbitmq;

import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelPoolOptionsTest {

    @Test
    void defaultValues() {
        var opts = new ChannelPoolOptions();
        assertThat(opts.getMaxCacheSize()).isNull();
        assertThat(opts.getSubscriptionScheduler()).isNull();
    }

    @Test
    void maxCacheSize() {
        var opts = new ChannelPoolOptions().maxCacheSize(10);
        assertThat(opts.getMaxCacheSize()).isEqualTo(10);
    }

    @Test
    void subscriptionScheduler() {
        var scheduler = Schedulers.boundedElastic();
        var opts = new ChannelPoolOptions().subscriptionScheduler(scheduler);
        assertThat(opts.getSubscriptionScheduler()).isSameAs(scheduler);
    }
}
