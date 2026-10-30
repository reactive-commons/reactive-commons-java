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
import com.rabbitmq.client.MissedHeartbeatException;
import com.rabbitmq.client.RecoverableChannel;
import com.rabbitmq.client.RecoverableConnection;
import com.rabbitmq.client.ShutdownSignalException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;

public abstract class Utils {

    public static <T> Mono<T> cache(Mono<T> mono) {
        return mono.cache(
                element -> Duration.ofMillis(Long.MAX_VALUE),
                throwable -> Duration.ZERO,
                () -> Duration.ZERO);
    }

    /**
     * Whether the connection is closed and the client will not re-open it: its automatic recovery is disabled, or the
     * application closed it, which the client never recovers.
     */
    public static boolean isClosedForGood(Connection connection) {
        if (connection.isOpen()) {
            return false;
        }
        if (!isRecoverable(connection)) {
            return true;
        }
        final ShutdownSignalException reason = connection.getCloseReason();
        return reason != null && reason.isInitiatedByApplication()
                && !(reason.getCause() instanceof MissedHeartbeatException);
    }

    static boolean isRecoverable(Connection connection) {
        return connection instanceof RecoverableConnection;
    }

    static boolean isRecoverable(Channel channel) {
        return channel instanceof RecoverableChannel;
    }

    /**
     * Opens a channel on the connection. The client opens none (it returns {@code null}) once the connection has every
     * channel it may open, its {@code channel_max}: that fails here, saying why, instead of further on with a
     * {@link NullPointerException}.
     */
    static Channel createChannel(Connection connection) throws IOException {
        final Channel channel = connection.createChannel();
        if (channel == null) {
            throw new IOException("No channel available: the connection reached its channel_max");
        }
        return channel;
    }

    @FunctionalInterface
    public interface ExceptionFunction<T, R> {
        R apply(T t) throws Exception;
    }
}
