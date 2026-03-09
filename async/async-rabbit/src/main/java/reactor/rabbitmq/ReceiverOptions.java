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

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

public class ReceiverOptions {

    private ConnectionFactory connectionFactory = ((Supplier<ConnectionFactory>) () -> {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.useNio();
        return connectionFactory;
    }).get();

    private Mono<? extends Connection> connectionMono;
    private Scheduler connectionSubscriptionScheduler;
    private Utils.ExceptionFunction<ConnectionFactory, ? extends Connection> connectionSupplier;
    private Function<Mono<? extends Connection>, Mono<? extends Connection>> connectionMonoConfigurator = cm -> cm;
    private Duration connectionClosingTimeout = Duration.ofSeconds(30);

    public ConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }

    public ReceiverOptions connectionFactory(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
        return this;
    }

    @Nullable
    public Scheduler getConnectionSubscriptionScheduler() {
        return connectionSubscriptionScheduler;
    }

    public ReceiverOptions connectionSubscriptionScheduler(@Nullable Scheduler connectionSubscriptionScheduler) {
        this.connectionSubscriptionScheduler = connectionSubscriptionScheduler;
        return this;
    }

    public ReceiverOptions connectionSupplier(
            Utils.ExceptionFunction<ConnectionFactory, ? extends Connection> function) {
        return this.connectionSupplier(this.connectionFactory, function);
    }

    public ReceiverOptions connectionSupplier(ConnectionFactory connectionFactory,
            Utils.ExceptionFunction<ConnectionFactory, ? extends Connection> function) {
        this.connectionSupplier = ignored -> function.apply(connectionFactory);
        return this;
    }

    public ReceiverOptions connectionMono(@Nullable Mono<? extends Connection> connectionMono) {
        this.connectionMono = connectionMono;
        return this;
    }

    public ReceiverOptions connectionMonoConfigurator(
            Function<Mono<? extends Connection>, Mono<? extends Connection>> connectionMonoConfigurator) {
        this.connectionMonoConfigurator = connectionMonoConfigurator;
        return this;
    }

    @Nullable
    public Mono<? extends Connection> getConnectionMono() {
        return connectionMono;
    }

    @Nullable
    public Utils.ExceptionFunction<ConnectionFactory, ? extends Connection> getConnectionSupplier() {
        return connectionSupplier;
    }

    public Function<Mono<? extends Connection>, Mono<? extends Connection>> getConnectionMonoConfigurator() {
        return connectionMonoConfigurator;
    }

    public ReceiverOptions connectionClosingTimeout(@Nullable Duration connectionClosingTimeout) {
        this.connectionClosingTimeout = connectionClosingTimeout;
        return this;
    }

    @Nullable
    public Duration getConnectionClosingTimeout() {
        return connectionClosingTimeout;
    }
}
