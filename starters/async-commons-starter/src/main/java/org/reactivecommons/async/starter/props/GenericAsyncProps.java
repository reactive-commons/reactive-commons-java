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

    public abstract void setConnectionProperties(P properties);

    public abstract P getConnectionProperties();

    public abstract String getBrokerType();

    public abstract boolean isEnabled();

    public abstract void setUseDiscardNotifierPerDomain(boolean enabled);
}