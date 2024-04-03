package org.reactivecommons.async.rabbit.config.props;

import lombok.Getter;
import lombok.Setter;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.exceptions.InvalidConfigurationException;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Getter
@Setter
public class AsyncPropsDomain extends HashMap<String, AsyncProps> {
    public AsyncPropsDomain(@Value("${spring.application.name}") String defaultAppName,
                            RabbitProperties defaultRabbitProperties,
                            AsyncPropsDomainProperties configured) {
        super(configured);
        this.computeIfAbsent(DEFAULT_DOMAIN, k -> new AsyncProps());
        super.forEach((key, value) -> { // To ensure that each domain has an appName
            if (value.getAppName() == null) {
                if (defaultAppName == null || defaultAppName.isEmpty()) {
                    throw new InvalidConfigurationException("defaultAppName does not has value and domain " + key
                            + " has not set the property appName. please use app.async." + key + ".appName property or " +
                            " spring.application.name property or withDefaultAppName in builder");
                }
                value.setAppName(defaultAppName);
            }
            if (value.getConnectionProperties() == null) {
                if (defaultRabbitProperties == null) {
                    throw new InvalidConfigurationException("Domain " + key + " could not be instantiated because no" +
                            " RabbitProperties properties found, please use withDefaultRabbitProperties or define the" +
                            "default " + key + " domain with properties explicitly");
                }
                value.setConnectionProperties(defaultRabbitProperties);
            }
            if (value.getBrokerConfigProps() == null) {
                value.setBrokerConfigProps(new BrokerConfigProps(value));
            }
        });
    }

    public AsyncProps getProps(String domain) {
        AsyncProps props = get(domain);
        if (props == null) {
            throw new InvalidConfigurationException("Domain " + domain + " id not defined");
        }
        return props;
    }

    public static AsyncPropsDomainBuilder builder() {
        return new AsyncPropsDomainBuilder();
    }

    public static class AsyncPropsDomainBuilder {
        private String defaultAppName;
        private RabbitProperties defaultRabbitProperties;
        private final HashMap<String, AsyncProps> domains = new HashMap<>();


        public AsyncPropsDomainBuilder withDefaultRabbitProperties(RabbitProperties defaultRabbitProperties) {
            this.defaultRabbitProperties = defaultRabbitProperties;
            return this;
        }


        public AsyncPropsDomainBuilder withDefaultAppName(String defaultAppName) {
            this.defaultAppName = defaultAppName;
            return this;
        }

        public AsyncPropsDomainBuilder withDomain(String domain, AsyncProps props) {
            domains.put(domain, props);
            return this;
        }

        public AsyncPropsDomain build() {
            AsyncPropsDomainProperties domainProperties = new AsyncPropsDomainProperties(domains);
            if (defaultRabbitProperties == null) {
                defaultRabbitProperties = new RabbitProperties();
            }
            return new AsyncPropsDomain(defaultAppName, defaultRabbitProperties, domainProperties);
        }

    }

}
