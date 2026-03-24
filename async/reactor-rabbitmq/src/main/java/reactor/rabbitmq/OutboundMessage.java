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

import com.rabbitmq.client.AMQP.BasicProperties;
import lombok.Getter;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Getter
public class OutboundMessage {

    private final String exchange;
    private final String routingKey;
    private final BasicProperties properties;
    private final byte[] body;
    private final Consumer<Boolean> ackNotifier;

    private final AtomicBoolean published = new AtomicBoolean(false);

    public OutboundMessage(String exchange, String routingKey, byte[] body) {
        this(exchange, routingKey, null, body, null);
    }

    public OutboundMessage(String exchange, String routingKey, BasicProperties properties, byte[] body) {
        this(exchange, routingKey, properties, body, null);
    }

    public OutboundMessage(String exchange, String routingKey, BasicProperties properties, byte[] body,
                           Consumer<Boolean> ackNotifier) {
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.properties = properties;
        this.body = body;
        this.ackNotifier = ackNotifier;
    }

    public boolean isPublished() {
        return published.get();
    }

    @Override
    public String toString() {
        return "OutboundMessage{" +
                "exchange='" + exchange + '\'' +
                ", routingKey='" + routingKey + '\'' +
                ", properties=" + properties +
                ", body=" + Arrays.toString(body) +
                '}';
    }

    void published() {
        this.published.set(true);
    }
}
