package org.reactivecommons.async.starter.config;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.starter.exceptions.InvalidConfigurationException;
import org.reactivecommons.async.starter.mybroker.props.AsyncMyBrokerPropsDomainProperties;
import org.reactivecommons.async.starter.mybroker.props.MyBrokerAsyncProps;
import org.reactivecommons.async.starter.mybroker.props.MyBrokerAsyncPropsDomain;
import org.reactivecommons.async.starter.mybroker.props.MyBrokerConnProps;
import org.reactivecommons.async.starter.props.GenericAsyncPropsDomain;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

/**
 * Every broker starter requires its own 'app' domain: when an application uses two brokers (e.g. RabbitMQ and Kafka),
 * one of them must disable it, otherwise one broker would replace the other silently.
 */
@SuppressWarnings("rawtypes")
class OneBrokerPerDomainTest {

    @Test
    void shouldFailWhenADomainIsEnabledInTwoBrokers() {
        List<GenericAsyncPropsDomain> brokers = List.of(
                broker("rabbitmq", true, "orders"),
                broker("kafka", true, "accounts"));

        assertThatThrownBy(() -> ReactiveCommonsConfig.validateOneBrokerPerDomain(brokers))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessage("Domain 'app' is enabled for both 'rabbitmq' and 'kafka'. A domain belongs to a single "
                        + "broker: disable it in one of them (app.async.app.enabled: false or "
                        + "reactive.commons.kafka.app.enabled: false).");
    }

    @Test
    void shouldAcceptTheDefaultDomainDisabledInOneOfTheBrokers() {
        List<GenericAsyncPropsDomain> brokers = List.of(
                broker("rabbitmq", true, "orders"),
                broker("kafka", false, "accounts"));

        assertThatCode(() -> ReactiveCommonsConfig.validateOneBrokerPerDomain(brokers)).doesNotThrowAnyException();
    }

    @Test
    void shouldFailWhenAnotherDomainIsEnabledInTwoBrokers() {
        List<GenericAsyncPropsDomain> brokers = List.of(
                broker("rabbitmq", true, "shared"),
                broker("kafka", false, "shared"));

        assertThatThrownBy(() -> ReactiveCommonsConfig.validateOneBrokerPerDomain(brokers))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("Domain 'shared' is enabled for both 'rabbitmq' and 'kafka'");
    }

    /**
     * The domains of one broker: 'app', enabled or not, and another domain, always enabled.
     */
    private static MyBrokerAsyncPropsDomain broker(String brokerType, boolean appEnabled, String otherDomain) {
        AsyncMyBrokerPropsDomainProperties configured = new AsyncMyBrokerPropsDomainProperties();
        configured.put(DEFAULT_DOMAIN, MyBrokerAsyncProps.builder().brokerType(brokerType).enabled(appEnabled)
                .build());
        configured.put(otherDomain, MyBrokerAsyncProps.builder().brokerType(brokerType).enabled(true).build());
        return new MyBrokerAsyncPropsDomain("sample", new MyBrokerConnProps(), configured, null);
    }
}
