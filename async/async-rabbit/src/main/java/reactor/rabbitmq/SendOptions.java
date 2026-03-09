/*
 * Copyright (c) 2018-2021 VMware Inc. or its affiliates.
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
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.function.BiConsumer;

public class SendOptions {

    private BiConsumer<Sender.SendContext, Exception> exceptionHandler = new ExceptionHandlers.RetrySendingExceptionHandler(
            Duration.ofSeconds(10), Duration.ofMillis(200),
            ExceptionHandlers.CONNECTION_RECOVERY_PREDICATE
    );

    private Integer maxInFlight;
    private boolean trackReturned;
    private Scheduler scheduler = Schedulers.immediate();
    private Mono<? extends Channel> channelMono;
    private BiConsumer<SignalType, Channel> channelCloseHandler;

    @Nullable
    public Integer getMaxInFlight() {
        return maxInFlight;
    }

    public boolean isTrackReturned() {
        return trackReturned;
    }

    public SendOptions trackReturned(boolean trackReturned) {
        this.trackReturned = trackReturned;
        return this;
    }

    public SendOptions maxInFlight(int maxInFlight) {
        this.maxInFlight = maxInFlight;
        return this;
    }

    public SendOptions maxInFlight(int maxInFlight, Scheduler scheduler) {
        this.maxInFlight = maxInFlight;
        this.scheduler = scheduler;
        return this;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public BiConsumer<Sender.SendContext, Exception> getExceptionHandler() {
        return exceptionHandler;
    }

    public SendOptions exceptionHandler(BiConsumer<Sender.SendContext, Exception> exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
        return this;
    }

    @Nullable
    public Mono<? extends Channel> getChannelMono() {
        return channelMono;
    }

    public SendOptions channelMono(@Nullable Mono<? extends Channel> channelMono) {
        this.channelMono = channelMono;
        return this;
    }

    @Nullable
    public BiConsumer<SignalType, Channel> getChannelCloseHandler() {
        return channelCloseHandler;
    }

    public SendOptions channelCloseHandler(@Nullable BiConsumer<SignalType, Channel> channelCloseHandler) {
        this.channelCloseHandler = channelCloseHandler;
        return this;
    }

    public SendOptions channelPool(ChannelPool channelPool) {
        this.channelMono = channelPool.getChannelMono();
        this.channelCloseHandler = channelPool.getChannelCloseHandler();
        return this;
    }
}
