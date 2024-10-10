package org.reactivecommons.async.starter.mybroker;

import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.broker.BrokerProviderFactory;
import org.reactivecommons.async.starter.broker.DiscardProvider;
import org.reactivecommons.async.starter.mybroker.props.MyBrokerAsyncProps;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service("mybroker")
public class MyBrokerProviderFactory implements BrokerProviderFactory<MyBrokerAsyncProps> {

    @Override
    public String getBrokerType() {
        return "mybroker";
    }

    @Override
    public DiscardProvider getDiscardProvider(MyBrokerAsyncProps props) {
        return () -> message -> Mono.empty();
    }

    @Override
    public BrokerProvider<MyBrokerAsyncProps> getProvider(String domain, MyBrokerAsyncProps props, DiscardProvider discardProvider) {
        return new MyBrokerProvider(domain, props, discardProvider);
    }
}
