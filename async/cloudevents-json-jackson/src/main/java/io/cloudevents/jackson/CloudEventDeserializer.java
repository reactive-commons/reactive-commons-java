/*
 * Copyright 2018-Present The CloudEvents Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package io.cloudevents.jackson;

import io.cloudevents.CloudEvent;
import io.cloudevents.CloudEventData;
import io.cloudevents.SpecVersion;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.core.data.BytesCloudEventData;
import io.cloudevents.rw.CloudEventContextWriter;
import io.cloudevents.rw.CloudEventDataMapper;
import io.cloudevents.rw.CloudEventRWException;
import io.cloudevents.rw.CloudEventReader;
import io.cloudevents.rw.CloudEventWriter;
import io.cloudevents.rw.CloudEventWriterFactory;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.node.JsonNodeType;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/**
 * Jackson {@link tools.jackson.databind.ValueSerializer} for {@link CloudEvent}
 */
class CloudEventDeserializer extends StdDeserializer<CloudEvent> {
    private final boolean forceExtensionNameLowerCaseDeserialization;
    private final boolean forceIgnoreInvalidExtensionNameDeserialization;
    private final boolean disableDataContentTypeDefaulting;

    protected CloudEventDeserializer(
            boolean forceExtensionNameLowerCaseDeserialization,
            boolean forceIgnoreInvalidExtensionNameDeserialization,
            boolean disableDataContentTypeDefaulting
    ) {
        super(CloudEvent.class);
        this.forceExtensionNameLowerCaseDeserialization = forceExtensionNameLowerCaseDeserialization;
        this.forceIgnoreInvalidExtensionNameDeserialization = forceIgnoreInvalidExtensionNameDeserialization;
        this.disableDataContentTypeDefaulting = disableDataContentTypeDefaulting;
    }

    private record JsonMessage(JsonParser p, ObjectNode node, boolean forceExtensionNameLowerCaseDeserialization,
                               boolean forceIgnoreInvalidExtensionNameDeserialization,
                               boolean disableDataContentTypeDefaulting) implements CloudEventReader {

        private static final String DATA_BASE64 = "data_base64";

        @Override
        public <T extends CloudEventWriter<V>, V> V read(CloudEventWriterFactory<T, V> writerFactory, CloudEventDataMapper<? extends CloudEventData> mapper) throws CloudEventRWException, IllegalStateException {
            try {
                SpecVersion specVersion = SpecVersion.parse(getStringNode(this.node, this.p, "specversion"));
                CloudEventWriter<V> writer = writerFactory.create(specVersion);

                // Read mandatory attributes
                for (String attr : specVersion.getMandatoryAttributes()) {
                    if (!"specversion".equals(attr)) {
                        writer.withContextAttribute(attr, getStringNode(this.node, this.p, attr));
                    }
                }

                // Parse datacontenttype if any
                String contentType = getOptionalStringNode(this.node, "datacontenttype");
                if (!this.disableDataContentTypeDefaulting && contentType == null && this.node.has("data")) {
                    contentType = "application/json";
                }
                if (contentType != null) {
                    writer.withContextAttribute("datacontenttype", contentType);
                }

                // Read optional attributes
                for (String attr : specVersion.getOptionalAttributes()) {
                    String val = getOptionalStringNode(this.node, attr);
                    if (val != null) {
                        writer.withContextAttribute(attr, val);
                    }
                }

                CloudEventData data = readData(contentType);
                readExtensions(writer);

                if (data != null) {
                    return writer.end(mapper.map(data));
                }
                return writer.end();
            } catch (IllegalArgumentException e) {
                throw MismatchedInputException.from(this.p, CloudEvent.class, e.getMessage());
            }
        }

        private CloudEventData readData(String contentType) {
            if (node.has(DATA_BASE64) && node.has("data")) {
                throw MismatchedInputException.from(p, CloudEvent.class,
                        "CloudEvent cannot have both 'data' and '" + DATA_BASE64 + "' fields");
            }
            if (node.has(DATA_BASE64)) {
                return BytesCloudEventData.wrap(node.remove(DATA_BASE64).binaryValue());
            }
            if (node.has("data")) {
                if (JsonFormat.dataIsJsonContentType(contentType)) {
                    // This solution is quite bad, but i see no alternatives now.
                    // Hopefully in future we can improve it
                    return JsonCloudEventData.wrap(node.remove("data"));
                }
                JsonNode dataNode = node.remove("data");
                assertNodeIsString(dataNode, "data", "Because content type is not a json, only a string is accepted as data");
                return BytesCloudEventData.wrap(dataNode.asString().getBytes(StandardCharsets.UTF_8));
            }
            return null;
        }

        private void readExtensions(CloudEventContextWriter writer) {
            node.properties().forEach(entry -> {
                String extensionName = entry.getKey();
                if (this.forceExtensionNameLowerCaseDeserialization) {
                    extensionName = extensionName.toLowerCase();
                }
                if (this.shouldSkipExtensionName(extensionName)) {
                    return;
                }
                writeExtensionAttribute(writer, extensionName, entry.getValue());
            });
        }

        private void writeExtensionAttribute(CloudEventContextWriter writer, String extensionName, JsonNode extensionValue) {
            switch (extensionValue.getNodeType()) {
                case BOOLEAN:
                    writer.withContextAttribute(extensionName, extensionValue.booleanValue());
                    break;
                case NUMBER:
                    final Number numericValue = extensionValue.numberValue();
                    if (numericValue instanceof Integer integer) {
                        writer.withContextAttribute(extensionName, integer);
                    } else {
                        throw CloudEventRWException.newInvalidAttributeType(extensionName, numericValue);
                    }
                    break;
                case STRING:
                    writer.withContextAttribute(extensionName, extensionValue.asString());
                    break;
                default:
                    writer.withContextAttribute(extensionName, extensionValue.toString());
            }
        }

        private String getStringNode(ObjectNode objNode, JsonParser p, String attributeName) {
            String val = getOptionalStringNode(objNode, attributeName);
            if (val == null) {
                throw MismatchedInputException.from(p, CloudEvent.class, "Missing mandatory " + attributeName + " attribute");
            }
            return val;
        }

        private String getOptionalStringNode(ObjectNode objNode, String attributeName) {
            JsonNode unparsedAttribute = objNode.remove(attributeName);
            if (unparsedAttribute == null || unparsedAttribute instanceof NullNode) {
                return null;
            }
            assertNodeIsString(unparsedAttribute, attributeName, null);
            return unparsedAttribute.asString();
        }

        private void assertNodeIsString(JsonNode node, String attributeName, String desc) {
            if (node.getNodeType() != JsonNodeType.STRING) {
                throw MismatchedInputException.from(
                        p,
                        CloudEvent.class,
                        "Wrong type " + node.getNodeType() + " for attribute " + attributeName
                                + ", expecting " + JsonNodeType.STRING + (desc != null ? ". " + desc : "")
                );
            }
        }

        // ignore not valid extension name
        private boolean shouldSkipExtensionName(String extensionName) {
            return this.forceIgnoreInvalidExtensionNameDeserialization && !this.isValidExtensionName(extensionName);
        }

        /**
         * Validates the extension name as defined in  CloudEvents spec.
         *
         * @param name the extension name
         * @return true if extension name is valid, false otherwise
         * @see <a href="https://github.com/cloudevents/spec/blob/main/spec.md#attribute-naming-convention">attribute-naming-convention</a>
         */
        private boolean isValidExtensionName(String name) {
            for (int i = 0; i < name.length(); i++) {
                if (!isValidChar(name.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        private boolean isValidChar(char c) {
            return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
        }

    }

    @Override
    public CloudEvent deserialize(JsonParser p, DeserializationContext ctxt) {
        // In future we could eventually find a better solution avoiding this buffering step, but now this is the best option
        // Other sdk does the same in order to support all versions
        ObjectNode node = ctxt.readValue(p, ObjectNode.class);

        try {
            return new JsonMessage(p, node, this.forceExtensionNameLowerCaseDeserialization, this.forceIgnoreInvalidExtensionNameDeserialization, this.disableDataContentTypeDefaulting)
                    .read(CloudEventBuilder::fromSpecVersion);
        } catch (RuntimeException e) {
            throw MismatchedInputException.from(p, CloudEvent.class, e.getMessage());
        }
    }
}