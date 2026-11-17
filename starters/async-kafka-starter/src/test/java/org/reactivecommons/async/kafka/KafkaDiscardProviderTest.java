package org.reactivecommons.async.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.DLQDiscardNotifier;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class KafkaDiscardProviderTest {
    @Mock
    private KafkaJacksonMessageConverter converter;
    @Mock
    private KafkaCustomizations customizations;

    @Test
    void shouldCreateDiscardNotifier() {
        // Arrange
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.setCheckExistingTopics(false);
        props.setConnectionProperties(new KafkaProperties());
        KafkaDiscardProvider discardProvider = new KafkaDiscardProvider(props, converter, customizations);
        // Act
        DiscardNotifier notifier = discardProvider.get();
        // Assert
        assertThat(notifier).isExactlyInstanceOf(DLQDiscardNotifier.class);
    }

    @Test
    void shouldCloseItsSenderOnceTheLastUserReleasesIt() {
        // Arrange
        AsyncKafkaProps props = new AsyncKafkaProps();
        props.setCheckExistingTopics(false);
        props.setConnectionProperties(new KafkaProperties());
        KafkaDiscardProvider discardProvider = new KafkaDiscardProvider(props, converter, customizations);
        DiscardNotifier notifier = discardProvider.get();
        discardProvider.retain();
        discardProvider.retain();
        // Act
        discardProvider.release();
        DiscardNotifier stillInUse = discardProvider.get();
        discardProvider.release();
        DiscardNotifier afterLastRelease = discardProvider.get();
        // Assert
        assertThat(stillInUse).as("kept while a user is left").isSameAs(notifier);
        assertThat(afterLastRelease).as("rebuilt after its sender was closed").isNotSameAs(notifier);
    }
}
