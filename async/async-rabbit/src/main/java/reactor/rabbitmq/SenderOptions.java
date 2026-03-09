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
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Scheduler;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class SenderOptions {

    private ConnectionFactory connectionFactory = ((Supplier<ConnectionFactory>) () -> {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.useNio();
        return connectionFactory;
    }).get();

    private Mono<? extends Connection> connectionMono;
    private Mono<? extends Channel> channelMono;
    private BiConsumer<SignalType, Channel> channelCloseHandler;
    private Scheduler resourceManagementScheduler;
    private Scheduler connectionSubscriptionScheduler;
    private Mono<? extends Channel> resourceManagementChannelMono;
    private Utils.ExceptionFunction<ConnectionFactory, ? extends Connection> connectionSupplier;
    private Function<Mono<? extends Connection>, Mono<? extends Connection>> connectionMonoConfigurator = cm -> cm;
    private Duration connectionClosingTimeout = Duration.ofSeconds(30);

    public ConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }

    public SenderOptions connectionFactory(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
        return this;
    }

    @Nullable
    public Scheduler getResourceManagementScheduler() {
        return resourceManagementScheduler;
    }

    public SenderOptions resourceManagementScheduler(@Nullable Scheduler resourceManagementScheduler) {
        this.resourceManagementScheduler = resourceManagementScheduler;
        return this;
    }

    @Nullable
    public Scheduler getConnectionSubscriptionScheduler() {
        return connectionSubscriptionScheduler;
    }

    public SenderOptions connectionSubscriptionScheduler(@Nullable Scheduler connectionSubscriptionScheduler) {
        this.connectionSubscriptionScheduler = connectionSubscriptionScheduler;
        return this;
    }

    public SenderOptions connectionSupplier(Utils.ExceptionFunction<ConnectionFactory, ? extends Connection> function) {
        return this.connectionSupplier(this.connectionFactory, function);
    }

    public SenderOptions connectionSupplier(ConnectionFactory connectionFactory,
            Utils.ExceptionFunction<ConnectionFactory, ? extends Connection> function) {
        this.connectionSupplier = ignored -> function.apply(connectionFactory);
        return this;
    }

    public SenderOptions connectionMono(@Nullable Mono<? extends Connection> connectionMono) {
        this.connectionMono = connectionMono;
        return this;
    }

    @Nullable
    public Mono<? extends Connection> getConnectionMono() {
        return connectionMono;
    }

    public SenderOptions channelMono(@Nullable Mono<? extends Channel> channelMono) {
        this.channelMono = channelMono;
        return this;
    }

    @Nullable
    public Mono<? extends Channel> getChannelMono() {
        return channelMono;
    }

    @Nullable
    public BiConsumer<SignalType, Channel> getChannelCloseHandler() {
        return channelCloseHandler;
    }

    public SenderOptions channelCloseHandler(@Nullable BiConsumer<SignalType, Channel> channelCloseHandler) {
        this.channelCloseHandler = channelCloseHandler;
        return this;
    }

    public SenderOptions channelPool(ChannelPool channelPool) {
        this.channelMono = channelPool.getChannelMono();
        this.channelCloseHandler = channelPool.getChannelCloseHandler();
        return this;
    }

    public SenderOptions connectionMonoConfigurator(
            Function<Mono<? extends Connection>, Mono<? extends Connection>> connectionMonoConfigurator) {
        this.connectionMonoConfigurator = connectionMonoConfigurator;
        return this;
    }

    public SenderOptions resourceManagementChannelMono(@Nullable Mono<? extends Channel> resourceManagementChannelMono) {
        this.resourceManagementChannelMono = resourceManagementChannelMono;
        return this;
    }

    @Nullable
    public Mono<? extends Channel> getResourceManagementChannelMono() {
        return resourceManagementChannelMono;
    }

    @Nullable
    public Utils.ExceptionFunction<ConnectionFactory, ? extends Connection> getConnectionSupplier() {
        return connectionSupplier;
    }

    public Function<Mono<? extends Connection>, Mono<? extends Connection>> getConnectionMonoConfigurator() {
        return connectionMonoConfigurator;
    }

    public SenderOptions connectionClosingTimeout(@Nullable Duration connectionClosingTimeout) {
        this.connectionClosingTimeout = connectionClosingTimeout;
        return this;
    }

    @Nullable
    public Duration getConnectionClosingTimeout() {
        return connectionClosingTimeout;
    }
}
