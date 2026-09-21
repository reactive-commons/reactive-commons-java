package org.reactivecommons.async.kafka.apicurio;

import com.networknt.schema.AbsoluteIri;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.resource.InputStreamSource;
import io.apicurio.registry.resolver.ParsedSchema;
import io.apicurio.registry.serde.jsonschema.JsonSchemaParser;
import io.apicurio.registry.utils.IoUtil;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link JsonSchemaParser} that only trusts the schemas coming from the Apicurio Registry.
 */
public class RestrictedJsonSchemaParser<T> extends JsonSchemaParser<T> {

    private static final String CLASSPATH_SCHEME = "classpath:";

    @Override
    public JsonSchema parseSchema(byte[] rawSchema, Map<String, ParsedSchema<JsonSchema>> resolvedReferences) {
        Map<String, String> referenceSchemas = new HashMap<>();
        collectReferences(resolvedReferences, referenceSchemas);

        JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7,
                builder -> builder.schemaLoaders(schemaLoaders -> schemaLoaders.schemas(referenceSchemas)
                        .add(RestrictedJsonSchemaParser::rejectRemoteReference)));

        JsonSchema schema = schemaFactory.getSchema(IoUtil.toString(rawSchema));
        schema.initializeValidators();
        return schema;
    }

    /**
     * Flattens the references resolved by the registry, keyed by the {@code $id} the schema uses to refer to them.
     */
    private void collectReferences(Map<String, ParsedSchema<JsonSchema>> resolvedReferences,
                                   Map<String, String> referenceSchemas) {
        resolvedReferences.forEach((referenceName, schema) -> {
            if (schema.hasReferences()) {
                Map<String, ParsedSchema<JsonSchema>> nested = new HashMap<>();
                schema.getSchemaReferences()
                        .forEach(reference -> nested.put(reference.getParsedSchema().getId(), reference));
                collectReferences(nested, referenceSchemas);
            }
            referenceSchemas.put(schema.getParsedSchema().getId(), IoUtil.toString(schema.getRawSchema()));
        });
    }

    /**
     * Terminates the loader chain for anything that was not provided by the registry.
     * <p>
     * Returning a non {@code null} source is what stops {@code DefaultSchemaLoader} from reaching its static
     * fallback chain. Classpath references are let through, because that is how the draft meta schemas bundled
     * with the library are read.
     */
    private static InputStreamSource rejectRemoteReference(AbsoluteIri iri) {
        String reference = iri == null ? null : iri.toString();
        if (reference == null || reference.startsWith(CLASSPATH_SCHEME)) {
            return null;
        }
        return () -> {
            throw new IOException("The schema reference " + reference + " is not resolvable through the Apicurio "
                    + "Registry. Reactive Commons does not download schemas from arbitrary locations, so every "
                    + "$ref must be registered as an artifact reference of the schema being validated.");
        };
    }
}
