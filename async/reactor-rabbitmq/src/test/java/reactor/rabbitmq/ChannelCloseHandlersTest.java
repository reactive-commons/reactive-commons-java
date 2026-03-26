package reactor.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.SignalType;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class ChannelCloseHandlersTest {

    @Test
    void closesOpenChannelOnOpenConnection() throws Exception {
        var channel = mock(Channel.class);
        var connection = mock(Connection.class);
        when(channel.isOpen()).thenReturn(true);
        when(channel.getConnection()).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);
        when(channel.getChannelNumber()).thenReturn(42);

        ChannelCloseHandlers.SENDER_CHANNEL_CLOSE_HANDLER_INSTANCE
                .accept(SignalType.ON_COMPLETE, channel);

        verify(channel).close();
    }

    @Test
    void doesNotCloseChannelWhenChannelAlreadyClosed() throws Exception {
        var channel = mock(Channel.class);
        when(channel.isOpen()).thenReturn(false);
        when(channel.getChannelNumber()).thenReturn(1);

        ChannelCloseHandlers.SENDER_CHANNEL_CLOSE_HANDLER_INSTANCE
                .accept(SignalType.ON_COMPLETE, channel);

        verify(channel, never()).close();
    }

    @Test
    void doesNotCloseChannelWhenConnectionClosed() throws Exception {
        var channel = mock(Channel.class);
        var connection = mock(Connection.class);
        when(channel.isOpen()).thenReturn(true);
        when(channel.getConnection()).thenReturn(connection);
        when(connection.isOpen()).thenReturn(false);
        when(channel.getChannelNumber()).thenReturn(2);

        ChannelCloseHandlers.SENDER_CHANNEL_CLOSE_HANDLER_INSTANCE
                .accept(SignalType.ON_COMPLETE, channel);

        verify(channel, never()).close();
    }

    @Test
    void handlesCloseExceptionGracefully() throws Exception {
        var channel = mock(Channel.class);
        var connection = mock(Connection.class);
        when(channel.isOpen()).thenReturn(true);
        when(channel.getConnection()).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);
        when(channel.getChannelNumber()).thenReturn(3);
        doThrow(new RuntimeException("close failed")).when(channel).close();

        // Should not throw
        Assertions.assertDoesNotThrow(() -> ChannelCloseHandlers.SENDER_CHANNEL_CLOSE_HANDLER_INSTANCE
                        .accept(SignalType.ON_ERROR, channel)
        );
    }
}
