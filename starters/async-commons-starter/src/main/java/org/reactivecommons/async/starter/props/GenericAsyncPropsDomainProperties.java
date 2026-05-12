package org.reactivecommons.async.starter.props;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
public class GenericAsyncPropsDomainProperties<T extends GenericAsyncProps<P>, P> extends LinkedHashMap<String, T> {

    public GenericAsyncPropsDomainProperties(Map<String, ? extends T> m) {
        super(m);
    }

    public GenericAsyncPropsDomainProperties() {
    }

    public static <T extends GenericAsyncProps<P>,
            P,
            X extends GenericAsyncPropsDomainProperties<T, P>> AsyncPropsDomainPropertiesBuilder<T, P, X>
    builder(Class<X> returnType) {
        return new AsyncPropsDomainPropertiesBuilder<>(returnType);
    }

    public static class AsyncPropsDomainPropertiesBuilder<T extends GenericAsyncProps<P>, P,
            X extends GenericAsyncPropsDomainProperties<T, P>> {
        private final Map<String, T> domains = new LinkedHashMap<>();
        private final Class<X> returnType;

        public AsyncPropsDomainPropertiesBuilder(Class<X> returnType) {
            this.returnType = returnType;
        }

        public AsyncPropsDomainPropertiesBuilder<T, P, X> withDomain(String domain, T props) {
            domains.put(domain, props);
            return this;
        }

        @SneakyThrows
        public X build() {
            return returnType.getDeclaredConstructor(Map.class).newInstance(domains);
        }
    }
}
