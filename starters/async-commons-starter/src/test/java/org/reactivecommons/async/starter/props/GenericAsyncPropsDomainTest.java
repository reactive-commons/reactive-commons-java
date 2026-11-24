package org.reactivecommons.async.starter.props;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.starter.exceptions.InvalidConfigurationException;
import org.reactivecommons.async.starter.mybroker.MyBrokerSecretFiller;
import org.reactivecommons.async.starter.mybroker.props.AsyncMyBrokerPropsDomainProperties;
import org.reactivecommons.async.starter.mybroker.props.MyBrokerAsyncProps;
import org.reactivecommons.async.starter.mybroker.props.MyBrokerAsyncPropsDomain;
import org.reactivecommons.async.starter.mybroker.props.MyBrokerConnProps;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        configured.put(DEFAULT_DOMAIN, new MyBrokerAsyncProps());

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

    @Test
    void aDomainWithoutAppNameFailsWithTheSameMessageWhenThereIsNoDefault() {
        String expected = "defaultAppName does not has value and domain app has not set the property appName."
                + " please use respective property or  spring.application.name property or withDefaultAppName"
                + " in builder";
        for (String defaultAppName : new String[]{null, ""}) {
            AsyncMyBrokerPropsDomainProperties configured = new AsyncMyBrokerPropsDomainProperties();
            configured.put(DEFAULT_DOMAIN, new MyBrokerAsyncProps());
            MyBrokerConnProps defaults = new MyBrokerConnProps();

            InvalidConfigurationException error = assertThrows(InvalidConfigurationException.class,
                    () -> new MyBrokerAsyncPropsDomain(defaultAppName, defaults, configured, null));

            assertEquals(expected, error.getMessage());
        }
    }

    @Test
    void aDomainWithoutConnectionPropertiesFailsWithTheSameMessageWhenThereAreNoDefaults() {
        AsyncMyBrokerPropsDomainProperties configured = new AsyncMyBrokerPropsDomainProperties();
        MyBrokerAsyncProps props = new MyBrokerAsyncProps();
        props.setAppName(OTHER);
        configured.put(DEFAULT_DOMAIN, props);

        InvalidConfigurationException error = assertThrows(InvalidConfigurationException.class,
                () -> new MyBrokerAsyncPropsDomain("sample", null, configured, null));

        String expected = "Domain app could not be instantiated because no properties found, please use"
                + " withDefaultProperties or define thedefault app domain with properties explicitly";
        assertEquals(expected, error.getMessage());
    }

    @Test
    void theAppNameIsCheckedBeforeTheConnectionProperties() {
        AsyncMyBrokerPropsDomainProperties configured = new AsyncMyBrokerPropsDomainProperties();
        configured.put(DEFAULT_DOMAIN, new MyBrokerAsyncProps());

        InvalidConfigurationException error = assertThrows(InvalidConfigurationException.class,
                () -> new MyBrokerAsyncPropsDomain(null, null, configured, null));

        assertTrue(error.getMessage().startsWith("defaultAppName does not has value"));
    }

    @Test
    void theDefaultsAreFilledBeforeTheSecretsAndTheSecretsBeforeTheCustoms() {
        AsyncMyBrokerPropsDomainProperties configured = new AsyncMyBrokerPropsDomainProperties();
        configured.put(DEFAULT_DOMAIN, new MyBrokerAsyncProps());
        MyBrokerConnProps defaults = new MyBrokerConnProps();
        defaults.setHost("localhost");
        List<String> seenBySecretFiller = new ArrayList<>();
        MyBrokerSecretFiller secretFiller = (domain, props) -> {
            seenBySecretFiller.add(domain + ":" + props.getAppName() + ":" + props.getConnectionProperties());
            props.setAppName(props.getAppName() + "+secret");
        };

        MyBrokerAsyncPropsDomain propsDomain = new MyBrokerAsyncPropsDomain("sample", defaults, configured,
                secretFiller) {
            @Override
            protected void fillCustoms(MyBrokerAsyncProps asyncProps) {
                asyncProps.setAppName(asyncProps.getAppName() + "+customs");
            }
        };

        MyBrokerAsyncProps props = propsDomain.getProps(DEFAULT_DOMAIN);
        assertEquals(List.of("app:sample:" + defaults), seenBySecretFiller);
        assertEquals("sample+secret+customs", props.getAppName());
        // the default connection properties are copied, never shared between domains
        assertEquals(defaults, props.getConnectionProperties());
        assertNotSame(defaults, props.getConnectionProperties());
    }


}
