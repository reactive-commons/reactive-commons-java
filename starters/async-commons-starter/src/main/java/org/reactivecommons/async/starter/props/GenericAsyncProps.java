package org.reactivecommons.async.starter.props;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public abstract class GenericAsyncProps<P> {
    private String appName;
    private String secret;

    abstract public void setConnectionProperties(P properties);

    abstract public P getConnectionProperties();

    abstract public String getBrokerType();

    abstract public boolean isEnabled();

    abstract public void setUseDiscardNotifierPerDomain(boolean enabled);
}