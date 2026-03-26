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


import lombok.Getter;

import java.util.Map;

@Getter
public class BindingSpecification {

    private String queue;
    private String exchange;
    private String exchangeTo;
    private String routingKey;
    private Map<String, Object> arguments;

    public static BindingSpecification binding() {
        return new BindingSpecification();
    }

    public static BindingSpecification binding(String exchange, String routingKey, String queue) {
        return new BindingSpecification().exchange(exchange).routingKey(routingKey).queue(queue);
    }

    public static BindingSpecification queueBinding(String exchange, String routingKey, String queue) {
        return binding(exchange, routingKey, queue);
    }

    public static BindingSpecification exchangeBinding(String exchangeFrom, String routingKey, String exchangeTo) {
        return new BindingSpecification().exchangeFrom(exchangeFrom).routingKey(routingKey).exchangeTo(exchangeTo);
    }

    public BindingSpecification queue(String queue) {
        this.queue = queue;
        return this;
    }

    public BindingSpecification exchange(String exchange) {
        this.exchange = exchange;
        return this;
    }

    public BindingSpecification exchangeFrom(String exchangeFrom) {
        this.exchange = exchangeFrom;
        return this;
    }

    public BindingSpecification exchangeTo(String exchangeTo) {
        this.exchangeTo = exchangeTo;
        return this;
    }

    public BindingSpecification routingKey(String routingKey) {
        this.routingKey = routingKey;
        return this;
    }

    public BindingSpecification arguments(Map<String, Object> arguments) {
        this.arguments = arguments;
        return this;
    }

}
