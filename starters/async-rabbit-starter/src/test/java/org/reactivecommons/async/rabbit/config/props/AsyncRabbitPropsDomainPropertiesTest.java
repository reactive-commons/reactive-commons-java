package org.reactivecommons.async.rabbit.config.props;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

class AsyncRabbitPropsDomainPropertiesTest {

    @Test
    void shouldMergeCustomizationsKeepingConfiguredValues() {
        var properties = new AsyncRabbitPropsDomainProperties();
        var fromYaml = new AsyncProps();
        fromYaml.getDomain().getEvents().setEventsSuffix("subsEvents");
        properties.put(DEFAULT_DOMAIN, fromYaml);

        AsyncProps result = properties.customize(DEFAULT_DOMAIN, app -> app.setRetryDelay(30000));

        assertThat(result).isSameAs(fromYaml);
        assertThat(result.getRetryDelay()).isEqualTo(30000);
        assertThat(result.getDomain().getEvents().getEventsSuffix()).isEqualTo("subsEvents");
    }

    @Test
    void shouldCreateDomainWithDefaultsWhenItDoesNotExist() {
        var properties = new AsyncRabbitPropsDomainProperties();

        AsyncProps result = properties.customize(DEFAULT_DOMAIN, app -> app.setRetryDelay(30000));

        assertThat(properties).containsKey(DEFAULT_DOMAIN);
        assertThat(result.getRetryDelay()).isEqualTo(30000);
        assertThat(result.getDomain().getEvents().getEventsSuffix()).isEqualTo("subsEvents");
    }
}
