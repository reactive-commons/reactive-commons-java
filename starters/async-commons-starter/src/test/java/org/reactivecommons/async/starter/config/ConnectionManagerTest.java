package org.reactivecommons.async.starter.config;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.starter.broker.BrokerProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SuppressWarnings("rawtypes")
class ConnectionManagerTest {

    @Test
    void startsEveryProvider() {
        BrokerProvider first = mock(BrokerProvider.class);
        BrokerProvider second = mock(BrokerProvider.class);
        ConnectionManager manager = new ConnectionManager()
                .addDomain("first", first)
                .addDomain("second", second);

        manager.start();

        verify(first).start();
        verify(second).start();
    }

    @Test
    void closesEveryProviderEvenWhenOneFails() {
        BrokerProvider failing = mock(BrokerProvider.class);
        BrokerProvider healthy = mock(BrokerProvider.class);
        doThrow(new RuntimeException("boom")).when(failing).close();
        ConnectionManager manager = new ConnectionManager()
                .addDomain("failing", failing)
                .addDomain("healthy", healthy);

        manager.close();

        verify(failing).close();
        verify(healthy).close();
        assertThat(manager.getProviders()).isEmpty();
    }

    @Test
    void closingTwiceDoesNotCloseTheProvidersAgain() {
        BrokerProvider provider = mock(BrokerProvider.class);
        ConnectionManager manager = new ConnectionManager().addDomain("domain", provider);

        manager.close();
        manager.close();

        verify(provider, times(1)).close();
    }
}
