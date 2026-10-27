/*
 * Copyright (c) 2018-2021 VMware Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.rabbitmq;

import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Command;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Method;
import com.rabbitmq.client.ShutdownSignalException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * Factory for creating a {@link Channel} proxy that only supports
 * {@link Channel#asyncCompletableRpc(Method)}, re-opening the underlying channel if necessary.
 * Used for resource management operations (declare, bind, unbind) in {@link Sender}.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ChannelProxy {

    /**
     * Creates a dynamic proxy implementing {@link Channel} where {@code asyncCompletableRpc} runs on the current
     * channel, opening a new one when it is closed. {@code isOpen} and {@code close} act on the current channel,
     * {@code equals} and {@code hashCode} use the identity of the proxy, and every other method throws
     * {@link UnsupportedOperationException}.
     */
    public static Channel create(Connection connection) throws IOException {
        return (Channel) Proxy.newProxyInstance(
                Channel.class.getClassLoader(),
                new Class<?>[]{Channel.class},
                new Handler(connection));
    }

    private static final class Handler implements InvocationHandler {
        private final Connection connection;
        private Channel delegate; // guarded by this
        private boolean closed; // guarded by this

        Handler(Connection connection) throws IOException {
            this.connection = connection;
            this.delegate = newChannel();
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Exception {
            return switch (method.getName()) {
                case "asyncCompletableRpc" -> asyncCompletableRpc((Method) args[0]);
                case "isOpen" -> isOpen();
                case "close" -> {
                    close();
                    yield null;
                }
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "ChannelProxy[delegate=" + currentDelegate() + "]";
                default -> throw new UnsupportedOperationException(
                        "ChannelProxy only supports asyncCompletableRpc, not " + method.getName());
            };
        }

        private CompletableFuture<Command> asyncCompletableRpc(Method method) throws IOException {
            final Channel channel = openChannel();
            try {
                return channel.asyncCompletableRpc(method);
            } catch (AlreadyClosedException e) {
                // closed between the check and the call, so the method was not sent: once on a new channel
                return reopen(channel).asyncCompletableRpc(method);
            }
        }

        private synchronized Channel openChannel() throws IOException {
            if (closed) {
                throw new AlreadyClosedException(new ShutdownSignalException(false, true, null, delegate));
            }
            if (delegate == null || !delegate.isOpen()) {
                delegate = newChannel();
            }
            return delegate;
        }

        private synchronized Channel reopen(Channel closedChannel) throws IOException {
            if (delegate == closedChannel) {
                delegate = null;
            }
            return openChannel();
        }

        private synchronized boolean isOpen() {
            return !closed && delegate != null && delegate.isOpen();
        }

        private synchronized void close() throws IOException, TimeoutException {
            closed = true;
            if (delegate != null && delegate.isOpen()) {
                delegate.close();
            }
        }

        private synchronized Channel currentDelegate() {
            return delegate;
        }

        private Channel newChannel() throws IOException {
            final Channel channel = connection.createChannel();
            if (channel == null) {
                throw new IOException("No channel available: the connection reached its channel_max");
            }
            return channel;
        }
    }
}
