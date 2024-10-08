package org.reactivecommons.async.starter.broker;

import org.reactivecommons.async.starter.props.GenericAsyncProps;

@SuppressWarnings("rawtypes")
public interface BrokerProviderFactory<T extends GenericAsyncProps> {
    String getBrokerType();

    DiscardProvider getDiscardProvider(T props);

    BrokerProvider<T> getProvider(String domain, T props, DiscardProvider discardProvider);

}
