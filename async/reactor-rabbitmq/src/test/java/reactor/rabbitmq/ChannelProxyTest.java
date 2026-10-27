package reactor.rabbitmq;

import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Method;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChannelProxyTest {

    @Test
    void asyncCompletableRpcDelegatesToRealChannel() throws Exception {
        var connection = mock(Connection.class);
        var realChannel = mock(Channel.class);
        when(connection.createChannel()).thenReturn(realChannel);
        when(realChannel.isOpen()).thenReturn(true);

        var command = mock(com.rabbitmq.client.Command.class);
        var future = CompletableFuture.completedFuture(command);
        when(realChannel.asyncCompletableRpc(any(Method.class))).thenReturn(future);

        Channel proxy = ChannelProxy.create(connection);
        var method = mock(Method.class);
        var result = proxy.asyncCompletableRpc(method);

        assertThat(result.get()).isSameAs(command);
        verify(realChannel).asyncCompletableRpc(method);
    }

    @Test
    void asyncCompletableRpcRecreatesChannelWhenClosed() throws Exception {
        var connection = mock(Connection.class);
        var closedChannel = mock(Channel.class);
        var newChannel = mock(Channel.class);
        when(connection.createChannel()).thenReturn(closedChannel, newChannel);
        when(closedChannel.isOpen()).thenReturn(false);
        when(newChannel.isOpen()).thenReturn(true);

        var command = mock(com.rabbitmq.client.Command.class);
        var future = CompletableFuture.completedFuture(command);
        when(newChannel.asyncCompletableRpc(any(Method.class))).thenReturn(future);

        Channel proxy = ChannelProxy.create(connection);
        var method = mock(Method.class);
        proxy.asyncCompletableRpc(method);

        // closedChannel was detected as not open, newChannel was created
        verify(connection, times(2)).createChannel();
        verify(newChannel).asyncCompletableRpc(method);
    }

    @Test
    void toStringReturnsReadableRepresentation() throws Exception {
        var connection = mock(Connection.class);
        var realChannel = mock(Channel.class);
        when(connection.createChannel()).thenReturn(realChannel);

        Channel proxy = ChannelProxy.create(connection);
        assertThat(proxy.toString()).startsWith("ChannelProxy[delegate=");
    }

    @Test
    void unsupportedMethodThrowsException() throws Exception {
        var connection = mock(Connection.class);
        var realChannel = mock(Channel.class);
        when(connection.createChannel()).thenReturn(realChannel);

        Channel proxy = ChannelProxy.create(connection);
        assertThatThrownBy(() -> proxy.basicPublish("", "", null, new byte[0]))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("basicPublish");
    }

    @Test
    void equalsAndHashCodeUseTheIdentityOfTheProxy() throws Exception {
        var connection = mock(Connection.class);
        when(connection.createChannel()).thenReturn(mock(Channel.class), mock(Channel.class));

        Channel proxy = ChannelProxy.create(connection);
        Channel other = ChannelProxy.create(connection);

        assertThat(proxy).isNotEqualTo(other);
        assertThat(new java.util.HashSet<>(java.util.List.of(proxy, other))).hasSize(2);
    }

    @Test
    void isOpenAndCloseDelegateToTheCurrentChannel() throws Exception {
        var connection = mock(Connection.class);
        var realChannel = mock(Channel.class);
        when(connection.createChannel()).thenReturn(realChannel);
        when(realChannel.isOpen()).thenReturn(true);

        Channel proxy = ChannelProxy.create(connection);
        assertThat(proxy.isOpen()).isTrue();
        proxy.close();

        verify(realChannel).close();
        assertThatThrownBy(() -> proxy.asyncCompletableRpc(mock(Method.class)))
                .as("a closed proxy does not open a new channel")
                .isInstanceOf(AlreadyClosedException.class);
    }

    @Test
    void noChannelAvailableIsReportedInsteadOfANullPointer() throws Exception {
        var connection = mock(Connection.class);
        var closedChannel = mock(Channel.class);
        when(connection.createChannel()).thenReturn(closedChannel, (Channel) null);
        when(closedChannel.isOpen()).thenReturn(false);

        Channel proxy = ChannelProxy.create(connection);

        assertThatThrownBy(() -> proxy.asyncCompletableRpc(mock(Method.class)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("channel_max");
    }

    @Test
    void aChannelClosedBetweenTheCheckAndTheRpcIsReplacedOnce() throws Exception {
        var connection = mock(Connection.class);
        var closingChannel = mock(Channel.class);
        var newChannel = mock(Channel.class);
        when(connection.createChannel()).thenReturn(closingChannel, newChannel);
        when(closingChannel.isOpen()).thenReturn(true, false);
        when(closingChannel.asyncCompletableRpc(any(Method.class))).thenThrow(new AlreadyClosedException(
                new ShutdownSignalException(false, false, null, closingChannel)));
        when(newChannel.isOpen()).thenReturn(true);
        var command = mock(com.rabbitmq.client.Command.class);
        when(newChannel.asyncCompletableRpc(any(Method.class))).thenReturn(CompletableFuture.completedFuture(command));

        Channel proxy = ChannelProxy.create(connection);

        assertThat(proxy.asyncCompletableRpc(mock(Method.class)).get()).isSameAs(command);
    }
}
