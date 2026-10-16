/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A JSON Schema, of the kind a tool's input takes: an object closed at its
 * level, its properties, and for a property its type, its closed set of
 * values, its description, and for a list the rule every element obeys.
 *
 * @param properties the properties of an object, by name, in declaration order;
 *                   absent on a schema of no object
 * @param required absent on a schema of no object
 * @param additionalProperties absent on a schema of no object
 * @param items absent on a schema of no list
 * @param minItems absent where a list may be empty or the schema is of no list
 * @param pattern absent where the elements obey no pattern
 * @param values absent where the value is not of a closed set
 * @param description absent on the outer object of a call
 */
public record SchemaPayload(
    @JsonProperty("type") String type,
    @JsonProperty("properties") @Nullable Map<String, SchemaPayload> properties,
    @JsonProperty("required") @Nullable List<String> required,
    @JsonProperty("additionalProperties") @Nullable Boolean additionalProperties,
    @JsonProperty("items") @Nullable SchemaPayload items,
    @JsonProperty("minItems") @Nullable Integer minItems,
    @JsonProperty("pattern") @Nullable String pattern,
    @JsonProperty("enum") @Nullable List<String> values,
    @JsonProperty("description") @Nullable String description) {
}
