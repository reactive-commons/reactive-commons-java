package org.reactivecommons.async.starter.props;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.starter.exceptions.InvalidConfigurationException;
import org.reactivecommons.async.starter.mybroker.MyBrokerSecretFiller;
import org.reactivecommons.async.starter.mybroker.props.AsyncMyBrokerPropsDomainProperties;
import org.reactivecommons.async.starter.mybroker.props.MyBrokerAsyncProps;
import org.reactivecommons.async.starter.mybroker.props.MyBrokerAsyncPropsDomain;
import org.reactivecommons.async.starter.mybroker.props.MyBrokerConnProps;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@SuppressWarnings("unchecked")
class GenericAsyncPropsDomainTest {

    public static final String OTHER = "other";

    @Test
    void shouldCreateProps() {
        // Arrange
        String defaultAppName = "sample";
        MyBrokerConnProps defaultMyBrokerProps = new MyBrokerConnProps();
        AsyncMyBrokerPropsDomainProperties configured = new AsyncMyBrokerPropsDomainProperties();
        MyBrokerAsyncProps other = new MyBrokerAsyncProps();
        other.setAppName(OTHER);
        configured.put(OTHER, other);
        MyBrokerSecretFiller secretFiller = (domain, props) -> {
        };
        MyBrokerAsyncPropsDomain propsDomain = new MyBrokerAsyncPropsDomain(
                defaultAppName, defaultMyBrokerProps, configured, secretFiller
        );
        // Act
        MyBrokerAsyncProps props = propsDomain.getProps(DEFAULT_DOMAIN);
        MyBrokerAsyncProps otherProps = propsDomain.getProps(OTHER);
        // Assert
        assertEquals("sample", props.getAppName());
        assertEquals(OTHER, otherProps.getAppName());
        assertThrows(InvalidConfigurationException.class, () -> propsDomain.getProps("non-existing-domain"));
    }

    @Test
    void shouldCreatePropsWithDefaultConnectionProperties() {
        // Arrange
        String defaultAppName = "sample";
        MyBrokerConnProps defaultMyBrokerProps = new MyBrokerConnProps();
        MyBrokerAsyncProps propsConfigured = new MyBrokerAsyncProps();
        MyBrokerAsyncPropsDomain propsDomain = MyBrokerAsyncPropsDomain.builder(MyBrokerConnProps.class,
                        AsyncMyBrokerPropsDomainProperties.class,
                        (Constructor<MyBrokerAsyncPropsDomain>) MyBrokerAsyncPropsDomain.class
                                .getDeclaredConstructors()[0]
                )
                .withDefaultAppName(defaultAppName)
                .withDefaultProperties(defaultMyBrokerProps)
                .withDomain(DEFAULT_DOMAIN, propsConfigured)
                .withSecretFiller(null)
                .build();
        // Act
        MyBrokerAsyncProps props = propsDomain.getProps(DEFAULT_DOMAIN);
        // Assert
        assertEquals("sample", props.getAppName());
        assertEquals(defaultMyBrokerProps, props.getConnectionProperties());
    }

    @Test
    void shouldFailCreatePropsWhenAppNameIsNullOrEmpty() {
        // Arrange
        MyBrokerConnProps defaultMyBrokerProps = new MyBrokerConnProps();
        AsyncMyBrokerPropsDomainProperties configured = new AsyncMyBrokerPropsDomainProperties();
        MyBrokerSecretFiller secretFiller = (domain, props) -> {
        };
        // Assert
        assertThrows(InvalidConfigurationException.class,
                // Act
                () -> new MyBrokerAsyncPropsDomain(null, defaultMyBrokerProps, configured, secretFiller));
        assertThrows(InvalidConfigurationException.class,
                // Act
                () -> new MyBrokerAsyncPropsDomain("", defaultMyBrokerProps, configured, secretFiller));
    }

    @Test
    void shouldFailCreatePropsWhenDefaultConnectionPropertiesAreNul() {
        // Arrange
        String defaultAppName = "sample";
        AsyncMyBrokerPropsDomainProperties configured = AsyncMyBrokerPropsDomainProperties
                .builder(AsyncMyBrokerPropsDomainProperties.class)
                .withDomain(OTHER, new MyBrokerAsyncProps())
                .build();
        MyBrokerSecretFiller secretFiller = (domain, props) -> {
        };
        // Assert
        assertThrows(InvalidConfigurationException.class,
                // Act
                () -> new MyBrokerAsyncPropsDomain(defaultAppName, null, configured, secretFiller));
    }


}
