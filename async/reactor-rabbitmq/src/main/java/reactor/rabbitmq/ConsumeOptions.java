/*
 * Copyright (c) 2017-2021 VMware Inc. or its affiliates, All Rights Reserved.
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
import com.rabbitmq.client.Delivery;
import lombok.Getter;
import reactor.core.publisher.FluxSink;

import java.util.Collections;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

@Getter
public class ConsumeOptions {

    private int qos = 250;
    private String consumerTag = "";
    private FluxSink.OverflowStrategy overflowStrategy = FluxSink.OverflowStrategy.BUFFER;

    private BiPredicate<Long, ? super Delivery> hookBeforeEmitBiFunction =
            (requestedFromDownstream, message) -> true;

    private BiPredicate<Long, ? super Delivery> stopConsumingBiFunction =
            (requestedFromDownstream, message) -> false;

    private BiConsumer<Receiver.AcknowledgmentContext, Exception> exceptionHandler =
            new ExceptionHandlers.FailFastAcknowledgmentExceptionHandler();

    private Consumer<Channel> channelCallback = ch -> {
    };

    /**
     * Called with the consumer tag once the broker has registered the consumer ({@code basic.consume-ok}), unlike
     * {@link #channelCallback} which runs before the consumer is even requested.
     */
    private Consumer<String> consumerRegisteredCallback = tag -> {
    };

    private Map<String, Object> arguments = Collections.emptyMap();

    public ConsumeOptions qos(int qos) {
        if (qos < 0) {
            throw new IllegalArgumentException("QoS must be greater or equal to 0");
        }
        this.qos = qos;
        return this;
    }

    public ConsumeOptions consumerTag(String consumerTag) {
        if (consumerTag == null) {
            throw new IllegalArgumentException("consumerTag must be non-null");
        }
        this.consumerTag = consumerTag;
        return this;
    }

    public ConsumeOptions overflowStrategy(FluxSink.OverflowStrategy overflowStrategy) {
        this.overflowStrategy = overflowStrategy;
        return this;
    }

    public ConsumeOptions hookBeforeEmitBiFunction(BiPredicate<Long, ? super Delivery> hookBeforeEmit) {
        this.hookBeforeEmitBiFunction = hookBeforeEmit;
        return this;
    }

    public ConsumeOptions stopConsumingBiFunction(BiPredicate<Long, ? super Delivery> stopConsumingBiFunction) {
        this.stopConsumingBiFunction = stopConsumingBiFunction;
        return this;
    }

    public ConsumeOptions exceptionHandler(BiConsumer<Receiver.AcknowledgmentContext, Exception> exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
        return this;
    }

    public ConsumeOptions channelCallback(Consumer<Channel> channelCallback) {
        this.channelCallback = channelCallback;
        return this;
    }

    public ConsumeOptions consumerRegisteredCallback(Consumer<String> consumerRegisteredCallback) {
        this.consumerRegisteredCallback = consumerRegisteredCallback;
        return this;
    }

    public ConsumeOptions arguments(Map<String, Object> arguments) {
        this.arguments = arguments;
        return this;
    }

}
