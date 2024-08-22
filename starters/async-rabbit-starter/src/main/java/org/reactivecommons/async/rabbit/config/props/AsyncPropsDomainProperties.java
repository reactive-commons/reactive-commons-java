package org.reactivecommons.async.rabbit.config.props;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.async")
public class AsyncPropsDomainProperties extends HashMap<String, AsyncProps> {

    public AsyncPropsDomainProperties() {
    }

    public AsyncPropsDomainProperties(Map<? extends String, ? extends AsyncProps> m) {
        super(m);
    }

    public static AsyncPropsDomainPropertiesBuilder builder() {
        return new AsyncPropsDomainPropertiesBuilder();
    }

    public static class AsyncPropsDomainPropertiesBuilder {
        private final HashMap<String, AsyncProps> domains = new HashMap<>();

        public AsyncPropsDomainPropertiesBuilder withDomain(String domain, AsyncProps props) {
            domains.put(domain, props);
            return this;
        }

        public AsyncPropsDomainProperties build() {
            return new AsyncPropsDomainProperties(domains);
        }
    }
}
