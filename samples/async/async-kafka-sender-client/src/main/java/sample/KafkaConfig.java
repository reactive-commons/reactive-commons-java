package sample;

import org.reactivecommons.async.kafka.config.RCKafkaConfig;
import org.reactivecommons.async.kafka.config.props.RCAsyncPropsKafka;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.nio.file.Path;

@Configuration
public class KafkaConfig {

    @Bean
    @Primary
    public RCAsyncPropsKafka kafkaProps() throws IOException {
        RCAsyncPropsKafka kafkaProps = new RCAsyncPropsKafka();
        kafkaProps.setCreateTopology(true);
        kafkaProps.setMaxRetries(5);
        kafkaProps.setRetryDelay(1000);
        kafkaProps.setWithDLQRetry(true);
        kafkaProps.setKafkaProps(RCKafkaConfig.readPropsFromDotEnv(Path.of(".kafka-env")));
        return kafkaProps;
    }
}
