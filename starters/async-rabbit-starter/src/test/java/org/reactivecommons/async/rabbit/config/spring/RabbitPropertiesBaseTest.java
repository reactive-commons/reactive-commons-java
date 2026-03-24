package org.reactivecommons.async.rabbit.config.spring;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitPropertiesBaseTest {

    private final RabbitPropertiesBase props = new RabbitPropertiesBase();

    @Test
    void defaultHost() {
        assertThat(props.getHost()).isEqualTo("localhost");
    }

    @Test
    void defaultPort() {
        assertThat(props.getPort()).isEqualTo(5672);
    }

    @Test
    void defaultCredentials() {
        assertThat(props.getUsername()).isEqualTo("guest");
        assertThat(props.getPassword()).isEqualTo("guest");
    }

    @Test
    void determineHostWithoutAddresses() {
        assertThat(props.determineHost()).isEqualTo("localhost");
    }

    @Test
    void determinePortWithoutAddresses() {
        assertThat(props.determinePort()).isEqualTo(5672);
    }

    @Test
    void determineAddressesWithoutParsed() {
        assertThat(props.determineAddresses()).isEqualTo("localhost:5672");
    }

    @Test
    void determineUsernameWithoutAddresses() {
        assertThat(props.determineUsername()).isEqualTo("guest");
    }

    @Test
    void determinePasswordWithoutAddresses() {
        assertThat(props.determinePassword()).isEqualTo("guest");
    }

    @Test
    void determineVirtualHostWithoutAddresses() {
        assertThat(props.determineVirtualHost()).isNull();
    }

    @Test
    void setVirtualHostEmpty() {
        props.setVirtualHost("");
        assertThat(props.getVirtualHost()).isEqualTo("/");
    }

    @Test
    void setVirtualHostNonEmpty() {
        props.setVirtualHost("myVhost");
        assertThat(props.getVirtualHost()).isEqualTo("myVhost");
    }

    @Nested
    class WithAddresses {

        @Test
        void parseSimpleAddress() {
            props.setAddresses("myhost:5673");
            assertThat(props.determineHost()).isEqualTo("myhost");
            assertThat(props.determinePort()).isEqualTo(5673);
            assertThat(props.determineAddresses()).isEqualTo("myhost:5673");
        }

        @Test
        void parseAddressWithDefaultPort() {
            props.setAddresses("myhost");
            assertThat(props.determineHost()).isEqualTo("myhost");
            assertThat(props.determinePort()).isEqualTo(5672);
        }

        @Test
        void parseMultipleAddresses() {
            props.setAddresses("host1:5672,host2:5673");
            assertThat(props.determineAddresses()).isEqualTo("host1:5672,host2:5673");
            assertThat(props.determineHost()).isEqualTo("host1");
            assertThat(props.determinePort()).isEqualTo(5672);
        }

        @Test
        void parseAmqpPrefixed() {
            props.setAddresses("amqp://myhost:5674");
            assertThat(props.determineHost()).isEqualTo("myhost");
            assertThat(props.determinePort()).isEqualTo(5674);
        }

        @Test
        void parseWithCredentials() {
            props.setAddresses("user:pass@myhost:5675");
            assertThat(props.determineUsername()).isEqualTo("user");
            assertThat(props.determinePassword()).isEqualTo("pass");
            assertThat(props.determineHost()).isEqualTo("myhost");
        }

        @Test
        void parseWithUsernameOnly() {
            props.setAddresses("user@myhost:5675");
            assertThat(props.determineHost()).isEqualTo("myhost");
        }

        @Test
        void parseWithVirtualHost() {
            props.setAddresses("myhost:5672/myvhost");
            assertThat(props.determineVirtualHost()).isEqualTo("myvhost");
        }

        @Test
        void parseWithEmptyVirtualHost() {
            props.setAddresses("myhost:5672/");
            assertThat(props.determineVirtualHost()).isEqualTo("/");
        }

        @Test
        void usernameFromAddressFallsBackToDefault() {
            props.setAddresses("myhost:5672");
            assertThat(props.determineUsername()).isEqualTo("guest");
        }

        @Test
        void passwordFromAddressFallsBackToDefault() {
            props.setAddresses("myhost:5672");
            assertThat(props.determinePassword()).isEqualTo("guest");
        }

        @Test
        void virtualHostFallsBackToDefault() {
            props.setAddresses("myhost:5672");
            props.setVirtualHost("fallback");
            assertThat(props.determineVirtualHost()).isEqualTo("fallback");
        }

        @Test
        void fullAmqpUri() {
            props.setAddresses("amqp://admin:secret@broker:5676/production");
            assertThat(props.determineHost()).isEqualTo("broker");
            assertThat(props.determinePort()).isEqualTo(5676);
            assertThat(props.determineUsername()).isEqualTo("admin");
            assertThat(props.determinePassword()).isEqualTo("secret");
            assertThat(props.determineVirtualHost()).isEqualTo("production");
        }
    }

    @Test
    void sslDefaults() {
        var ssl = props.getSsl();
        assertThat(ssl.isEnabled()).isFalse();
        assertThat(ssl.getKeyStoreType()).isEqualTo("PKCS12");
        assertThat(ssl.getTrustStoreType()).isEqualTo("JKS");
        assertThat(ssl.isValidateServerCertificate()).isTrue();
        assertThat(ssl.isVerifyHostname()).isTrue();
    }

    @Test
    void cacheDefaults() {
        var cache = props.getCache();
        assertThat(cache.getChannel()).isNotNull();
        assertThat(cache.getConnection()).isNotNull();
        assertThat(cache.getConnection().getMode()).isEqualTo("CHANNEL");
    }

    @Test
    void listenerDefaults() {
        var listener = props.getListener();
        assertThat(listener.getType()).isEqualTo(RabbitPropertiesBase.ContainerType.SIMPLE);
        assertThat(listener.getSimple()).isNotNull();
        assertThat(listener.getDirect()).isNotNull();
        assertThat(listener.getSimple().isMissingQueuesFatal()).isTrue();
        assertThat(listener.getDirect().isMissingQueuesFatal()).isFalse();
    }

    @Test
    void templateDefaults() {
        var template = props.getTemplate();
        assertThat(template.getExchange()).isEmpty();
        assertThat(template.getRoutingKey()).isEmpty();
    }

    @Test
    void retryDefaults() {
        var retry = props.getTemplate().getRetry();
        assertThat(retry.isEnabled()).isFalse();
        assertThat(retry.getMaxAttempts()).isEqualTo(3);
    }

    @Test
    void listenerRetryDefaults() {
        var listenerRetry = props.getListener().getSimple().getRetry();
        assertThat(listenerRetry.isStateless()).isTrue();
    }
}
