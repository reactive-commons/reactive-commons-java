package org.reactivecommons.async.starter.props;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

@Getter
@Setter
@NoArgsConstructor
public class GenericAsyncPropsDomainProperties<T extends GenericAsyncProps<P>, P> extends HashMap<String, T> {

    public GenericAsyncPropsDomainProperties(Map<String, ? extends T> m) {
        super(m);
    }

    /**
     * Applies the given customizer over the properties of a domain, keeping every value already bound from
     * your configuration files (application.yaml/properties), so YAML and programmatic configuration are merged.
     * When the domain is not present yet, it is created with default values.
     * <p>
     * Use this instead of {@link #put(Object, Object)} when you only want to override some properties, because
     * put replaces the whole domain, discarding the values bound from your configuration files.
     *
     * @param domain     domain name, for example 'app'
     * @param customizer mutations to apply over the domain properties
     * @return the customized domain properties
     */
    public T customize(String domain, Consumer<T> customizer) {
        T props = computeIfAbsent(domain, key -> createProps());
        customizer.accept(props);
        return props;
    }

    /**
     * Creates a domain properties instance with default values. Subclasses override it to support
     * {@link #customize(String, Consumer)} for domains that are not present in the configuration files.
     *
     * @return a new domain properties instance with default values
     */
    protected T createProps() {
        throw new UnsupportedOperationException(getClass().getName() + " cannot create domain properties. Please "
                + "declare the domain in your configuration file or define it with put(domain, props)");
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

        @SneakyThrows
        public X build() {
            return returnType.getDeclaredConstructor(Map.class).newInstance(domains);
        }
    }
}
