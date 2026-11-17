package org.reactivecommons.async.starter.impl.common.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.reactivecommons.async.commons.converters.json.ObjectMapperSupplier;
import org.reactivecommons.async.kafka.KafkaBrokerProviderFactory;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.kafka.config.KafkaPropertiesAutoConfig;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomainProperties;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import org.reactivecommons.async.kafka.metrics.KafkaMetricsFactory;
import org.reactivecommons.async.kafka.metrics.MicrometerKafkaMetricsFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@EnableConfigurationProperties({KafkaPropertiesAutoConfig.class, AsyncKafkaPropsDomainProperties.class})
@Import({AsyncKafkaPropsDomain.class, KafkaBrokerProviderFactory.class})
public class RCKafkaConfig {

    @Bean
    @ConditionalOnMissingBean(KafkaCustomizations.class)
    public KafkaCustomizations defaultKafkaCustomizations() {
        return new KafkaCustomizations();
    }

    @Bean
    @ConditionalOnMissingBean(KafkaJacksonMessageConverter.class)
    public KafkaJacksonMessageConverter kafkaJacksonMessageConverter(ObjectMapperSupplier objectMapperSupplier) {
        return new KafkaJacksonMessageConverter(objectMapperSupplier.get());
    }

    @Bean
    @ConditionalOnMissingBean(AsyncKafkaPropsDomain.KafkaSecretFiller.class)
    public AsyncKafkaPropsDomain.KafkaSecretFiller defaultKafkaSecretFiller() {
        return (ignoredDomain, ignoredProps) -> {
        };
    }

    @Bean
    @ConditionalOnMissingBean(KafkaProperties.class)
    public KafkaProperties defaultKafkaProperties(KafkaPropertiesAutoConfig properties, ObjectMapperSupplier supplier) {
        return supplier.get().convertValue(properties, KafkaProperties.class);
    }

    @Bean
    @ConditionalOnMissingBean(SslBundles.class)
    public SslBundles defaultSslBundles() {
        return new DefaultSslBundleRegistry();
    }

    /**
     * Only with Micrometer in the classpath: the metrics of the Kafka clients and of the listeners are published in the
     * {@code MeterRegistry} of the application, and nothing is recorded when there is none.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class KafkaMicrometerConfig {

        @Bean
        @ConditionalOnMissingBean(KafkaMetricsFactory.class)
        public KafkaMetricsFactory micrometerKafkaMetricsFactory(ObjectProvider<MeterRegistry> registry) {
            return new MicrometerKafkaMetricsFactory(registry);
        }
    }
}
