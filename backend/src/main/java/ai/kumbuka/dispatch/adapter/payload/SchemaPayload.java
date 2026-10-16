/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * A JSON Schema, of the kind a tool's input takes: an object closed at its
 * level, its properties, and for a property its type, its closed set of
 * values, its description, and for a list the rule every element obeys.
 *
 * @param properties the properties of an object, by name, in declaration order
 */
public record SchemaPayload(
    @JsonProperty("type") String type,
    @JsonProperty("properties") Map<String, SchemaPayload> properties,
    @JsonProperty("required") List<String> required,
    @JsonProperty("additionalProperties") Boolean additionalProperties,
    @JsonProperty("items") SchemaPayload items,
    @JsonProperty("minItems") Integer minItems,
    @JsonProperty("pattern") String pattern,
    @JsonProperty("enum") List<String> values,
    @JsonProperty("description") String description) {
}
