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

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Method;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Factory for creating a {@link Channel} proxy that only supports
 * {@link Channel#asyncCompletableRpc(Method)}, re-opening the underlying channel if necessary.
 * Used for resource management operations (declare, bind, unbind) in {@link Sender}.
 */
public final class ChannelProxy {

    private ChannelProxy() {
    }

    /**
     * Creates a dynamic proxy implementing {@link Channel} where only
     * {@code asyncCompletableRpc} is functional. All other methods throw
     * {@link UnsupportedOperationException}.
     */
    public static Channel create(Connection connection) throws IOException {
        var delegate = new AtomicReference<>(connection.createChannel());
        var lock = new ReentrantLock();

        return (Channel) Proxy.newProxyInstance(
                Channel.class.getClassLoader(),
                new Class<?>[]{Channel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "asyncCompletableRpc" -> {
                        if (!delegate.get().isOpen()) {
                            lock.lock();
                            try {
                                if (!delegate.get().isOpen()) {
                                    delegate.set(connection.createChannel());
                                }
                            } finally {
                                lock.unlock();
                            }
                        }
                        yield delegate.get().asyncCompletableRpc((Method) args[0]);
                    }
                    case "toString" ->
                            "ChannelProxy[delegate=" + delegate.get() + "]";
                    default -> throw new UnsupportedOperationException(
                            "ChannelProxy only supports asyncCompletableRpc, not " + method.getName());
                }
        );
    }
}
