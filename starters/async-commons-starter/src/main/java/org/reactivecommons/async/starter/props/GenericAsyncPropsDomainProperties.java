package org.reactivecommons.async.starter.props;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
public class GenericAsyncPropsDomainProperties<T extends GenericAsyncProps<P>, P> extends HashMap<String, T> {

    public GenericAsyncPropsDomainProperties(Map<? extends String, ? extends T> m) {
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
        private final Map<String, T> domains = new HashMap<>();
        private final Class<X> returnType;

        public AsyncPropsDomainPropertiesBuilder(Class<X> returnType) {
            this.returnType = returnType;
        }

        public AsyncPropsDomainPropertiesBuilder<T, P, X> withDomain(String domain, T props) {
            domains.put(domain, props);
            return this;
        }

        public X build() {
            return returnType.cast(new GenericAsyncPropsDomainProperties<>(domains));
        }
    }
}
