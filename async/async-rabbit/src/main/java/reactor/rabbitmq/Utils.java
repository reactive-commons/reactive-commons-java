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
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.RecoverableChannel;
import com.rabbitmq.client.RecoverableConnection;
import reactor.core.publisher.Mono;

import java.time.Duration;

public abstract class Utils {

    public static <T> Mono<T> cache(Mono<T> mono) {
        return mono.cache(
                element -> Duration.ofMillis(Long.MAX_VALUE),
                throwable -> Duration.ZERO,
                () -> Duration.ZERO);
    }

    static boolean isRecoverable(Connection connection) {
        return connection instanceof RecoverableConnection;
    }

    static boolean isRecoverable(Channel channel) {
        return channel instanceof RecoverableChannel;
    }

    @FunctionalInterface
    public interface ExceptionFunction<T, R> {
        R apply(T t) throws Exception;
    }
}
