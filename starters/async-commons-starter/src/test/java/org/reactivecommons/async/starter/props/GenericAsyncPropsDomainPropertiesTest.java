package org.reactivecommons.async.starter.props;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.starter.mybroker.props.AsyncMyBrokerPropsDomainProperties;
import org.reactivecommons.async.starter.mybroker.props.MyBrokerAsyncProps;
import org.reactivecommons.async.starter.mybroker.props.MyBrokerConnProps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenericAsyncPropsDomainPropertiesTest {

    @Test
    void shouldApplyCustomizationsOverExistingDomain() {
        var properties = new AsyncMyBrokerPropsDomainProperties();
        MyBrokerAsyncProps configured = new MyBrokerAsyncProps();
        configured.setAppName("configured-app");
        properties.put("app", configured);

        MyBrokerAsyncProps result = properties.customize("app", props -> props.setSecret("secret-name"));

        assertThat(result).isSameAs(configured);
        assertThat(result.getAppName()).isEqualTo("configured-app");
        assertThat(result.getSecret()).isEqualTo("secret-name");
    }

    @Test
    void shouldInitializeConnectionPropertiesWithDefaultsBeforeCustomizing() {
        var properties = new AsyncMyBrokerPropsDomainProperties();
        MyBrokerAsyncProps configured = new MyBrokerAsyncProps();
        properties.put("app", configured);
        properties.useDefaultConnectionProperties(MyBrokerConnProps::new);

        properties.customize("app", props -> assertThat(props.getConnectionProperties()).isNotNull());

        assertThat(configured.getConnectionProperties()).isNotNull();
    }

    @Test
    void shouldKeepConnectionPropertiesWhenAlreadyDefined() {
        var properties = new AsyncMyBrokerPropsDomainProperties();
        MyBrokerAsyncProps configured = new MyBrokerAsyncProps();
        MyBrokerConnProps existing = new MyBrokerConnProps();
        configured.setConnectionProperties(existing);
        properties.put("app", configured);
        properties.useDefaultConnectionProperties(MyBrokerConnProps::new);

        properties.customize("app", props -> {
        });

        assertThat(configured.getConnectionProperties()).isSameAs(existing);
    }

    @Test
    void shouldFailWhenDomainIsAbsentAndSubclassCannotCreateProps() {
        var properties = new AsyncMyBrokerPropsDomainProperties();

        assertThatThrownBy(() -> properties.customize("app", props -> props.setSecret("secret-name")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("cannot create domain properties");
    }
}
