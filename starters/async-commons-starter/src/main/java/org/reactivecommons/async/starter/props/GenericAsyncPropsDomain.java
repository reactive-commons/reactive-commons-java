package org.reactivecommons.async.starter.props;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.reactivecommons.async.starter.exceptions.InvalidConfigurationException;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;


@Getter
@Setter
public class GenericAsyncPropsDomain<T extends GenericAsyncProps<P>, P> extends HashMap<String, T> {
    private Class<T> asyncPropsClass;
    private Class<P> propsClass;

    public GenericAsyncPropsDomain(String defaultAppName,
                                   String groupId,
                                   P defaultProperties,
                                   GenericAsyncPropsDomainProperties<T, P> configured,
                                   SecretFiller<P> secretFiller,
                                   Class<T> asyncPropsClass,
                                   Class<P> propsClass) {
        super(configured);
        this.propsClass = propsClass;
        this.asyncPropsClass = asyncPropsClass;
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        this.computeIfAbsent(DEFAULT_DOMAIN, k -> {
            T defaultApp = AsyncPropsDomainBuilder.instantiate(asyncPropsClass);
            defaultApp.setConnectionProperties(mapper.convertValue(defaultProperties, propsClass));
            return defaultApp;
        });
        super.forEach((key, value) -> { // To ensure that each domain has an appName
            if (value.getAppName() == null) {
                if (defaultAppName == null || defaultAppName.isEmpty()) {
                    throw new InvalidConfigurationException("defaultAppName does not has value and domain " + key
                            + " has not set the property appName. please use respective property or " +
                            " spring.application.name property or withDefaultAppName in builder");
                }
                if(value.getGroupId() != null){
                    value.setAppName(groupId);
                }
                else {
                    value.setAppName(defaultAppName + "-events");
                }

            }
            if (value.getConnectionProperties() == null) {
                if (defaultProperties == null) {
                    throw new InvalidConfigurationException("Domain " + key + " could not be instantiated because no" +
                            " properties found, please use withDefaultProperties or define the" +
                            "default " + key + " domain with properties explicitly");
                }
                value.setConnectionProperties(mapper.convertValue(defaultProperties, propsClass));
            }
            if (secretFiller != null) {
                secretFiller.fillWithSecret(key, value);
            }
            fillCustoms(value);
        });
    }

    protected void fillCustoms(T asyncProps) {
        // To be overridden called after the default properties are set
    }

    public T getProps(String domain) {
        T props = get(domain);
        if (props == null) {
            throw new InvalidConfigurationException("Domain " + domain + " id not defined");
        }
        return props;
    }

    // Static builder strategy

    public static <
            T extends GenericAsyncProps<P>,
            P,
            X extends GenericAsyncPropsDomainProperties<T, P>,
            R extends GenericAsyncPropsDomain<T, P>>
    AsyncPropsDomainBuilder<T, P, X, R> builder(Class<P> propsClass,
                                                Class<X> asyncPropsDomainClass,
                                                Constructor<R> returnType) {
        return new AsyncPropsDomainBuilder<>(propsClass, asyncPropsDomainClass, returnType);
    }

    public static class AsyncPropsDomainBuilder<
            T extends GenericAsyncProps<P>,
            P,
            X extends GenericAsyncPropsDomainProperties<T, P>,
            R extends GenericAsyncPropsDomain<T, P>> {
        private final Class<P> propsClass;
        private final Class<X> asyncPropsDomainClass;
        private final Constructor<R> returnType;
        private String defaultAppName;
        private final HashMap<String, T> domains = new HashMap<>();
        private P defaultProperties;
        private SecretFiller<P> secretFiller;

        public AsyncPropsDomainBuilder(Class<P> propsClass, Class<X> asyncPropsDomainClass,
                                       Constructor<R> returnType) {
            this.propsClass = propsClass;
            this.asyncPropsDomainClass = asyncPropsDomainClass;
            this.returnType = returnType;
        }

        public AsyncPropsDomainBuilder<T, P, X, R> withDefaultProperties(P defaultProperties) {
            this.defaultProperties = defaultProperties;
            return this;
        }


        public AsyncPropsDomainBuilder<T, P, X, R> withDefaultAppName(String defaultAppName) {
            this.defaultAppName = defaultAppName;
            return this;
        }


        public AsyncPropsDomainBuilder<T, P, X, R> withSecretFiller(SecretFiller<P> secretFiller) {
            this.secretFiller = secretFiller;
            return this;
        }

        public AsyncPropsDomainBuilder<T, P, X, R> withDomain(String domain, T props) {
            domains.put(domain, props);
            return this;
        }

        @SneakyThrows
        public R build() {
            X domainProperties = instantiate(asyncPropsDomainClass, domains);
            if (defaultProperties == null) {
                defaultProperties = instantiate(propsClass);
            }
            return returnType.newInstance(defaultAppName, defaultProperties, domainProperties, secretFiller);
        }

        @SneakyThrows
        private static <X> X instantiate(Class<X> xClass) {
            return xClass.getDeclaredConstructor().newInstance();
        }

        @SneakyThrows
        private static <X> X instantiate(Class<X> xClass, Map<?, ?> arg) {
            return xClass.getDeclaredConstructor(Map.class).newInstance(arg);
        }

    }

    public interface SecretFiller<P> {
        void fillWithSecret(String domain, GenericAsyncProps<P> props);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        GenericAsyncPropsDomain<?, ?> that = (GenericAsyncPropsDomain<?, ?>) o;
        return Objects.equals(asyncPropsClass, that.asyncPropsClass) && Objects.equals(propsClass, that.propsClass);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), asyncPropsClass, propsClass);
    }
}
