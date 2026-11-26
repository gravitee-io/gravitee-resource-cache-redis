/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.resource.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import io.gravitee.json.validation.InvalidJsonException;
import io.gravitee.json.validation.JsonSchemaValidatorImpl;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Validates configurations against the bundled resource schema, which embeds the shared platform
 * definitions, the way the Management API does.
 */
class SchemaFormValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String schema;

    private static JsonNode sslBranches;

    private final JsonSchemaValidatorImpl validator = new JsonSchemaValidatorImpl();

    @BeforeAll
    static void loadSchema() throws Exception {
        try (InputStream in = SchemaFormValidationTest.class.getResourceAsStream("/schemas/schema-form.json")) {
            JsonNode root = MAPPER.readTree(in);
            assertThat(root.at("/gioExternalDefinitions/sslOptions").isObject())
                .as("bundled schema with the shared definitions, built at process-resources")
                .isTrue();
            schema = MAPPER.writeValueAsString(root);
            sslBranches = root.at("/properties/ssl/oneOf");
        }
    }

    @Test
    void should_accept_legacy_boolean_ssl_true() {
        assertThatCode(() -> validator.validate(schema, "{\"useSsl\":true,\"ssl\":true}")).doesNotThrowAnyException();
    }

    @Test
    void should_accept_legacy_boolean_ssl_false() {
        assertThatCode(() -> validator.validate(schema, "{\"useSsl\":false,\"ssl\":false}")).doesNotThrowAnyException();
    }

    @Test
    void should_accept_ssl_options_object() {
        assertThatCode(() ->
            validator.validate(schema, "{\"useSsl\":true,\"ssl\":{\"hostnameVerifier\":false}}")
        ).doesNotThrowAnyException();
    }

    @Test
    void should_reject_incomplete_trust_store() {
        assertThatThrownBy(() -> validator.validate(schema, "{\"useSsl\":true,\"ssl\":{\"trustStore\":{\"type\":\"JKS\"}}}")).isInstanceOf(
            InvalidJsonException.class
        );
    }

    @Test
    void should_title_every_ssl_option() {
        assertThat(sslBranches)
            .isNotEmpty()
            .allSatisfy(option -> assertThat(option.path("title").asText()).isNotBlank());
    }

    @Test
    void should_pin_each_legacy_boolean_option_to_its_value() {
        assertThat(sslBranches)
            .filteredOn(option -> "boolean".equals(option.path("type").asText()))
            .extracting(option -> option.get("const"))
            .containsExactlyInAnyOrder(BooleanNode.TRUE, BooleanNode.FALSE);
    }

    @Test
    void should_reject_ssl_of_another_type() {
        assertThatThrownBy(() -> validator.validate(schema, "{\"ssl\":\"yes\"}")).isInstanceOf(InvalidJsonException.class);
    }
}
