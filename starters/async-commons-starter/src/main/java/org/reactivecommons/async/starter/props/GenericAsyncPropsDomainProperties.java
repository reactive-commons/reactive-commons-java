package org.reactivecommons.async.starter.props;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

// java:S2160: equality is the one of the map itself (the domains). The supplier is only transient wiring used while
// customizing, it is not part of the state, so it must not take part in equals/hashCode.
@SuppressWarnings("java:S2160")
@Getter
@Setter
@NoArgsConstructor
public class GenericAsyncPropsDomainProperties<T extends GenericAsyncProps<P>, P> extends HashMap<String, T> {

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private transient Supplier<P> defaultConnectionPropertiesSupplier;

    public GenericAsyncPropsDomainProperties(Map<String, ? extends T> m) {
        super(m);
    }

    /**
     * Registers the supplier used by {@link #customize(String, Consumer)} to create
     * connection properties for domains that do not define them. The supplier should
     * return a new instance initialized with the broker's default values, allowing
     * customizers to safely read and modify {@code props.getConnectionProperties()}
     * without null checks.
     *
     * @param supplier supplier of connection properties initialized with default values
     */
    public void useDefaultConnectionProperties(Supplier<P> supplier) {
        this.defaultConnectionPropertiesSupplier = supplier;
    }

    /**
     * Applies the given customizer over the properties of a domain, keeping every value already bound from
     * your configuration files (application.yaml/properties), so YAML and programmatic configuration are merged.
     * When the domain is not present yet, it is created with default values. When the domain has no connection
     * properties, they are initialized with the default connection properties (see
     * {@link #useDefaultConnectionProperties(Supplier)}) before the customizer is applied, so
     * {@code getConnectionProperties()} is never null inside the customizer.
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
        if (props.getConnectionProperties() == null && defaultConnectionPropertiesSupplier != null) {
            props.setConnectionProperties(defaultConnectionPropertiesSupplier.get());
        }
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

    /**
     * @deprecated in favor of {@link #customize(String, Consumer)}, used through each broker's
     * {@code *PropsCustomizer} bean (e.g. {@code RabbitPropsCustomizer}, {@code KafkaPropsCustomizer}), which keeps
     * the hybrid YAML + programmatic model. This builder replaces the whole domain properties instance built here,
     * discarding any values already bound from configuration files.
     */
    @Deprecated(forRemoval = true, since = "7.4.0")
    public static <T extends GenericAsyncProps<P>,
            P,
            X extends GenericAsyncPropsDomainProperties<T, P>> AsyncPropsDomainPropertiesBuilder<T, P, X>
    builder(Class<X> returnType) {
        return new AsyncPropsDomainPropertiesBuilder<>(returnType);
    }

    /**
     * @deprecated in favor of {@link #customize(String, Consumer)}, used through each broker's
     * {@code *PropsCustomizer} bean, which keeps the hybrid YAML + programmatic model.
     */
    @Deprecated(forRemoval = true, since = "7.4.0")
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
