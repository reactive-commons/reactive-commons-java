/*
 * Copyright (c) 2019-2021 VMware Inc. or its affiliates, All Rights Reserved.
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
import com.rabbitmq.client.ShutdownSignalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;

import static reactor.rabbitmq.ChannelCloseHandlers.SENDER_CHANNEL_CLOSE_HANDLER_INSTANCE;

class LazyChannelPool implements ChannelPool {

    private static final Logger LOGGER = LoggerFactory.getLogger(LazyChannelPool.class);
    private static final int DEFAULT_CHANNEL_POOL_SIZE = 5;

    private final Mono<? extends Connection> connectionMono;
    private final BlockingQueue<Channel> channelsQueue;
    private final Scheduler subscriptionScheduler;

    LazyChannelPool(Mono<? extends Connection> connectionMono, ChannelPoolOptions channelPoolOptions) {
        int channelsQueueCapacity = channelPoolOptions.getMaxCacheSize() == null ?
                DEFAULT_CHANNEL_POOL_SIZE : channelPoolOptions.getMaxCacheSize();
        this.channelsQueue = new LinkedBlockingQueue<>(channelsQueueCapacity);
        this.connectionMono = connectionMono;
        this.subscriptionScheduler = channelPoolOptions.getSubscriptionScheduler() == null ?
                Schedulers.newBoundedElastic(
                        Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE,
                        Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
                        "sender-channel-pool") :
                channelPoolOptions.getSubscriptionScheduler();
    }

    public Mono<? extends Channel> getChannelMono() {
        return connectionMono.map(connection -> {
            Channel channel = pollUsableChannel();
            if (channel == null) {
                channel = createChannel(connection);
            } else {
                channel.clearConfirmListeners();
                channel.clearReturnListeners();
            }
            return channel;
        }).subscribeOn(subscriptionScheduler);
    }

    /**
     * Returns a pooled channel, skipping the ones closed while idle that will never be usable again. A channel closed
     * by a connection failure is kept: with automatic recovery the same instance is re-opened by the client.
     */
    private Channel pollUsableChannel() {
        Channel channel;
        while ((channel = channelsQueue.poll()) != null) {
            if (channel.isOpen() || willBeRecovered(channel)) {
                return channel;
            }
            ShutdownSignalException reason = channel.getCloseReason();
            LOGGER.debug("Discarding pooled channel {} closed by: {}", channel.getChannelNumber(),
                    reason != null ? reason.getMessage() : "unknown reason");
        }
        return null;
    }

    private static boolean willBeRecovered(Channel channel) {
        ShutdownSignalException reason = channel.getCloseReason();
        return Utils.isRecoverable(channel) && reason != null
                && reason.isHardError() && !reason.isInitiatedByApplication();
    }

    @Override
    public BiConsumer<SignalType, Channel> getChannelCloseHandler() {
        return (signalType, channel) -> {
            if (!channel.isOpen()) {
                forgetClosedChannel(channel);
                return;
            }
            boolean offer = signalType == SignalType.ON_COMPLETE && channelsQueue.offer(channel);
            if (!offer) {
                SENDER_CHANNEL_CLOSE_HANDLER_INSTANCE.accept(signalType, channel);
            }
        };
    }

    /**
     * A recoverable channel closed by a connection failure stays registered in its connection and would be re-opened
     * by the automatic recovery although nobody uses it anymore. Aborting it unregisters it.
     */
    private static void forgetClosedChannel(Channel channel) {
        if (Utils.isRecoverable(channel)) {
            try {
                channel.abort();
            } catch (Exception e) {
                LOGGER.debug("Error while aborting closed channel {}: {}", channel.getChannelNumber(), e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        List<Channel> channels = new ArrayList<>();
        channelsQueue.drainTo(channels);
        channels.forEach(channel ->
            SENDER_CHANNEL_CLOSE_HANDLER_INSTANCE.accept(SignalType.ON_COMPLETE, channel)
        );
    }

    private Channel createChannel(Connection connection) {
        try {
            return connection.createChannel();
        } catch (IOException e) {
            throw new RabbitFluxException("Error while creating channel", e);
        }
    }
}
