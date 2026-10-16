/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.mcp;

import ai.kumbuka.dispatch.adapter.payload.CallPayload;
import ai.kumbuka.dispatch.adapter.payload.SchemaPayload;
import ai.kumbuka.dispatch.adapter.payload.ToolPayload;
import ai.kumbuka.dispatch.surface.Argument;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Texts;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The twenty-five tools of the assistant surface, generated from the
 * declaration (C4.2, ADR-0039).
 *
 * <p><strong>Generated, not written.</strong> Names, descriptions and schemas
 * are a projection of {@link ProcessVerb} into the shape {@code tools/list}
 * asks for: a tool is named by its call's identity, {@code dispatch_<verb>}.
 * The tests that hold this list take their expected values from the target
 * and the concept document, written into the tests by hand, so generating here
 * removes a copy without making those tests circular.
 *
 * <h2>Two levels, both closed</h2>
 *
 * The schema has an inner {@code fields} object wherever the call writes
 * anything, and {@code additionalProperties: false} stands on both. The value
 * of {@code metadata} is the caller's own keys and is an object with no
 * declared members; it is a value under a closed level, not a level.
 */
public final class McpTools {

    private static final String OBJECT = "object";

    private McpTools() {
    }

    /** The twenty-five, in the order of the declaration. */
    public static List<ToolPayload> declared() {
        return Arrays.stream(ProcessVerb.values())
            .map(verb -> new ToolPayload(verb.call(), verb.description(), schemaOf(verb)))
            .toList();
    }

    /** The tool names, in the order of the declaration: each the identity of its call. */
    public static List<String> names() {
        return Arrays.stream(ProcessVerb.values()).map(ProcessVerb::call).toList();
    }

    /** The input schema of one call: top-level arguments, plus {@code fields} where it writes. */
    private static SchemaPayload schemaOf(ProcessVerb verb) {
        Map<String, SchemaPayload> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Argument argument : verb.topArguments()) {
            properties.put(argument.name(), property(argument));
            if (argument.required()) {
                required.add(argument.name());
            }
        }
        if (verb.hasFields()) {
            properties.put(CallPayload.FIELDS, fieldsSchema(verb));
            if (verb.fieldArguments().stream().anyMatch(Argument::required)) {
                required.add(CallPayload.FIELDS);
            }
        }
        return closedObject(properties, required, null);
    }

    /** The inner object: everything the call writes into the task, closed in its own right. */
    private static SchemaPayload fieldsSchema(ProcessVerb verb) {
        Map<String, SchemaPayload> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Argument argument : verb.fieldArguments()) {
            properties.put(argument.name(), property(argument));
            if (argument.required()) {
                required.add(argument.name());
            }
        }
        return closedObject(properties, required, Texts.arguments().fields());
    }

    /**
     * One argument's schema: its type and description, the closed set of its
     * values where it has one, and for a list the rule every element obeys —
     * the same rule the surface enforces, read from the declaration.
     */
    private static SchemaPayload property(Argument argument) {
        boolean list = Argument.ARRAY.equals(argument.type());
        SchemaPayload items = list
            ? new SchemaPayload(Argument.STRING, null, null, null, null, null,
                argument.itemPattern(), null, null)
            : null;
        return new SchemaPayload(argument.type(), null, null, null, items,
            list && argument.required() ? 1 : null, null,
            argument.values().isEmpty() ? null : argument.valueNames(),
            argument.description());
    }

    /** A JSON Schema object with {@code additionalProperties} closed. */
    private static SchemaPayload closedObject(Map<String, SchemaPayload> properties,
                                              List<String> required,
                                              @Nullable String description) {
        return new SchemaPayload(OBJECT, properties, List.copyOf(required), false, null, null,
            null, null, description);
    }
}
