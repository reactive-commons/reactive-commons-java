package org.reactivecommons.async.kafka;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.kafka.config.KafkaProperties;
import reactor.kafka.sender.SenderOptions;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaSetupUtilsTest {

    @Test
    void shouldNotStopTheSendSequenceOnRecordErrors() {
        // A record that fails (e.g. topic missing with auto-creation disabled) must fail only its own send
        SenderOptions<String, byte[]> options = KafkaSetupUtils.createSenderOptions(new KafkaProperties());

        assertThat(options.stopOnError()).isFalse();
    }
}
