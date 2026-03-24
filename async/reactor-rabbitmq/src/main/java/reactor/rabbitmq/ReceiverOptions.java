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
import lombok.Getter;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.function.UnaryOperator;

@Getter
public class ReceiverOptions {

    private ConnectionFactory connectionFactory = new ConnectionFactory();

    private Mono<? extends Connection> connectionMono;
    private Scheduler connectionSubscriptionScheduler;
    private Utils.ExceptionFunction<ConnectionFactory, ? extends Connection> connectionSupplier;
    private UnaryOperator<Mono<? extends Connection>> connectionMonoConfigurator = cm -> cm;
    private Duration connectionClosingTimeout = Duration.ofSeconds(30);

    public ReceiverOptions connectionFactory(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
        return this;
    }

    public ReceiverOptions connectionSubscriptionScheduler(Scheduler connectionSubscriptionScheduler) {
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

    public ReceiverOptions connectionMono(Mono<? extends Connection> connectionMono) {
        this.connectionMono = connectionMono;
        return this;
    }

    public ReceiverOptions connectionMonoConfigurator(
            UnaryOperator<Mono<? extends Connection>> connectionMonoConfigurator) {
        this.connectionMonoConfigurator = connectionMonoConfigurator;
        return this;
    }

    public ReceiverOptions connectionClosingTimeout(Duration connectionClosingTimeout) {
        this.connectionClosingTimeout = connectionClosingTimeout;
        return this;
    }

}
