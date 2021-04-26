package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.rabbit.RabbitMessage;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class RabbitMessageTest {

    @Test
    public void shouldCreateFromDelivery() {
        Envelope env = new Envelope(2, false, "exchange", "routeKey");
        Map<String, Object> headers = new HashMap<>();
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder().contentType("content").contentEncoding("utf8").headers(headers).build();
        byte[] body = new byte[]{3, 4, 5, 6};
        Delivery delivery = new Delivery(env, props, body);

        final RabbitMessage message = RabbitMessage.fromDelivery(delivery);
        assertThat(message.getBody()).isEqualTo(body);
        assertThat(message.getProperties().getContentEncoding()).isEqualTo(props.getContentEncoding());
        assertThat(message.getProperties().getContentType()).isEqualTo(props.getContentType());
        assertThat(message.getProperties().getHeaders()).isEqualTo(headers);

    }

}