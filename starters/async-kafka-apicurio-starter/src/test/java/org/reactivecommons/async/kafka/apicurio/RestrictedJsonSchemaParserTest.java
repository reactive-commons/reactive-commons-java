package org.reactivecommons.async.kafka.apicurio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import io.apicurio.registry.resolver.ParsedSchema;
import io.apicurio.registry.resolver.ParsedSchemaImpl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestrictedJsonSchemaParserTest {

    private final RestrictedJsonSchemaParser<Object> parser = new RestrictedJsonSchemaParser<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldParseAPlainSchema() throws Exception {
        JsonSchema schema = parser.parseSchema("""
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": { "name": { "type": "string" } },
                  "required": ["name"]
                }
                """.getBytes(StandardCharsets.UTF_8), new HashMap<>());

        assertThat(schema.validate(objectMapper.readTree("{\"name\":\"john\"}"))).isEmpty();
        assertThat(schema.validate(objectMapper.readTree("{}"))).isNotEmpty();
    }

    @Test
    void shouldResolveTheReferencesProvidedByTheRegistry() throws Exception {
        String address = """
                {
                  "$id": "https://schemas.example.com/address.json",
                  "type": "object",
                  "properties": { "city": { "type": "string" } },
                  "required": ["city"]
                }
                """;
        Map<String, ParsedSchema<JsonSchema>> references = new HashMap<>();
        references.put("address.json", parsedSchema("https://schemas.example.com/address.json", address));

        JsonSchema schema = parser.parseSchema("""
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": { "address": { "$ref": "https://schemas.example.com/address.json" } },
                  "required": ["address"]
                }
                """.getBytes(StandardCharsets.UTF_8), references);

        assertThat(schema.validate(objectMapper.readTree("{\"address\":{\"city\":\"medellin\"}}"))).isEmpty();
        assertThat(schema.validate(objectMapper.readTree("{\"address\":{}}"))).isNotEmpty();
    }

    @Test
    void shouldNotDownloadASchemaThatTheRegistryDidNotProvide() throws IOException {
        // A port nobody is listening on: if the reference were fetched the failure would be a connection error
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        String schema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": { "evil": { "$ref": "http://127.0.0.1:%d/schema.json" } }
                }
                """.formatted(unusedPort);

        assertThatThrownBy(() -> parser.parseSchema(schema.getBytes(StandardCharsets.UTF_8), new HashMap<>()))
                .hasStackTraceContaining("not resolvable through the Apicurio Registry");
    }

    @Test
    void shouldFailWhileParsingAndNotWhileValidating() {
        // The references are loaded eagerly, so an unusable schema never reaches the message hot path
        AtomicInteger parsed = new AtomicInteger();
        String schema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "$ref": "https://schemas.example.com/missing.json"
                }
                """;

        assertThatThrownBy(() -> {
            parsed.incrementAndGet();
            parser.parseSchema(schema.getBytes(StandardCharsets.UTF_8), new HashMap<>());
        }).isNotNull();
        assertThat(parsed).hasValue(1);
    }

    @Test
    void shouldStillReadTheMetaSchemasFromTheClasspath() throws Exception {
        JsonSchema schema = parser.parseSchema("""
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "string",
                  "format": "uuid"
                }
                """.getBytes(StandardCharsets.UTF_8), new HashMap<>());

        assertThat(schema.validate(objectMapper.readTree("\"not-a-uuid\""))).isNotNull();
    }

    private ParsedSchema<JsonSchema> parsedSchema(String id, String rawSchema) {
        JsonSchema parsed = parser.parseSchema(rawSchema.getBytes(StandardCharsets.UTF_8), new HashMap<>());
        return new ParsedSchemaImpl<JsonSchema>()
                .setParsedSchema(parsed)
                .setRawSchema(rawSchema.getBytes(StandardCharsets.UTF_8))
                .setReferenceName(id)
                .setSchemaReferences(List.of());
    }
}
