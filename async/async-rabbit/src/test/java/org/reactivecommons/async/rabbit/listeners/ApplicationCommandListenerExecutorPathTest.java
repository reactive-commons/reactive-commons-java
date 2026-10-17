package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.RabbitMessage;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.Receiver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Resolution of the handler path of a command. It runs for every message on the consumer dispatch thread, before
 * the handler deserializes the whole body again, so it must not build the full JSON tree of the message.
 */
class ApplicationCommandListenerExecutorPathTest {

    private static final JsonMapper MAPPER = new JsonMapper();

    private final MessageConverter converter = spy(new RabbitJacksonMessageConverter(MAPPER));
    private final ApplicationCommandListener listener = new ApplicationCommandListener(
            new ReactiveMessageListener(mock(Receiver.class), mock(TopologyCreator.class)), "queue", null,
            "directMessages", converter, false, false, false, -1, 1000, Optional.empty(),
            mock(DiscardNotifier.class), mock(CustomReporter.class));

    @Test
    void commandPathIsItsNameAndTheBodyIsNotParsedIntoATree() {
        String body = MAPPER.writeValueAsString(new Command<>("app.command.create", "id-1", Map.of("k", "v")));

        assertThat(listener.getExecutorPath(delivery(body, null))).isEqualTo("app.command.create");
        verify(converter, never()).readValue(any(Message.class), eq(JsonNode.class));
    }

    @Test
    void commandPathDoesNotDependOnTheFieldOrder() {
        String body = "{\"data\":{\"name\":\"nested\",\"type\":\"nested\"},\"commandId\":\"id-1\","
                + "\"name\":\"app.command.create\"}";

        assertThat(listener.getExecutorPath(delivery(body, null))).isEqualTo("app.command.create");
    }

    @Test
    void cloudEventPathIsItsType() {
        String body = "{\"specversion\":\"1.0\",\"id\":\"1\",\"source\":\"/app\",\"type\":\"app.command.cloud\","
                + "\"data\":{\"name\":\"nested\",\"commandId\":\"nested\"}}";

        assertThat(listener.getExecutorPath(delivery(body, null))).isEqualTo("app.command.cloud");
    }

    /**
     * Without top-level commandId or type the path is empty (RabbitMessage.fromDelivery(delivery).getType()).
     */
    @Test
    void messageWithoutCommandIdNorTypeHasAnEmptyPath() {
        assertThat(listener.getExecutorPath(delivery("{\"any\":{\"type\":\"nested\"}}", "amqp.type")))
                .as("only top-level fields are considered")
                .isEmpty();
        assertThat(listener.getExecutorPath(delivery("\"just a json string\"", "amqp.type"))).isEmpty();
    }

    @Test
    void invalidJsonFailsWithAConversionError() {
        AcknowledgableDelivery delivery = delivery("{not json", null);

        assertThatThrownBy(() -> listener.getExecutorPath(delivery)).isInstanceOf(MessageConversionException.class);
    }

    @Test
    void streamedCommandNameMatchesTheOneFromTheFullJsonTree() {
        String[] bodies = {
                "{\"name\":\"a\",\"commandId\":\"1\",\"data\":{}}",
                "{\"commandId\":\"1\",\"data\":[1,{\"name\":\"deep\"}],\"name\":\"a\"}",
                "{\"name\":\"a\",\"data\":{}}",                       // no commandId: not a domain command
                "{\"commandId\":\"1\",\"data\":{}}",                  // domain command without name
                "{\"type\":\"t\",\"data\":{\"commandId\":\"nested\",\"name\":\"nested\"}}",
                "{\"commandId\":\"1\",\"name\":\"a\",\"type\":\"t\"}",
                "{\"commandId\":null,\"name\":\"a\"}",
                "{\"name\":7,\"commandId\":\"1\"}",
                "{\"type\":null}",
                "{}", "[]", "\"text\"", "12"
        };
        for (String body : bodies) {
            Message message = RabbitMessage.fromDelivery(delivery(body, null));
            assertThat(converter.readCommandName(message))
                    .as("command name of %s", body)
                    .isEqualTo(fromTree(message));
        }
    }

    @Test
    void aNameOrTypeThatIsNotTextFailsLikeTheFullJsonTreeDoes() {
        for (String body : new String[]{"{\"name\":{\"x\":1},\"commandId\":\"1\"}", "{\"type\":[1]}"}) {
            Message message = RabbitMessage.fromDelivery(delivery(body, null));
            assertThatThrownBy(() -> fromTree(message)).as("full tree for %s", body).isNotNull();
            assertThatThrownBy(() -> converter.readCommandName(message)).as("streamed for %s", body)
                    .isInstanceOf(MessageConversionException.class);
        }
    }

    /**
     * The result of the previous implementation, which built the whole JSON tree of the body.
     */
    private static String fromTree(Message message) {
        JsonNode tree = MAPPER.readValue(message.getBody(), JsonNode.class);
        if (tree.get("commandId") != null && tree.get("name") != null) {
            return tree.get("name").asString();
        }
        if (tree.get("type") != null) {
            return tree.get("type").asString();
        }
        return null;
    }

    /**
     * Resolving the path of a large command must not cost a full parse of the body: the handler parses it anyway.
     */
    @Test
    void resolvingThePathOfALargeCommandIsMuchCheaperThanParsingItsWholeBody() {
        String body = MAPPER.writeValueAsString(new Command<>("app.command.big", "id-1", Map.of("payload",
                "x".repeat(200_000), "items", java.util.Collections.nCopies(5_000, Map.of("a", 1, "b", "two")))));
        AcknowledgableDelivery delivery = delivery(body, null);
        Message message = RabbitMessage.fromDelivery(delivery);

        long fullParse = bestOf(() -> MAPPER.readValue(message.getBody(), JsonNode.class));
        long pathResolution = bestOf(() -> listener.getExecutorPath(delivery));
        System.out.printf("[PERF] large command (%d bytes): full JSON tree %d us, path resolution %d us%n",
                body.length(), fullParse / 1_000, pathResolution / 1_000);

        assertThat(pathResolution).as("path resolution (ns) vs full parse (ns)").isLessThan(fullParse / 5);
    }

    private static long bestOf(Runnable action) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 30; i++) {
            long start = System.nanoTime();
            action.run();
            best = Math.min(best, System.nanoTime() - start);
        }
        return best;
    }

    private static AcknowledgableDelivery delivery(String body, String type) {
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder().type(type).headers(Map.of()).build();
        return new AcknowledgableDelivery(new Delivery(new Envelope(1L, false, "directMessages", "queue"),
                properties, body.getBytes(StandardCharsets.UTF_8)), null, null);
    }
}
