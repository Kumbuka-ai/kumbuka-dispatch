/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.architecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool schema is a projection of the declaration (C4.7): no tool schema
 * enumerates an argument's values and leaves out a value the declaration
 * declares for it.
 *
 * <p>For every argument the declaration declares with {@code values}, the
 * check finds the tool of the same call and the argument's property in its
 * input schema, at the top level or under {@code fields} as the argument's
 * {@code placement} says. Where that property carries an {@code enum}, every
 * declared value must stand in it. A property without {@code enum} leaves the
 * argument open, which C4.7 admits; a property that is missing altogether is
 * reported, because a closed schema without it refuses every declared value.
 *
 * <h2>Where the served schemas are read</h2>
 *
 * The tool list and the declaration carry the texts of the message bundles,
 * which only the running service builds. So this class holds the check and
 * observes it reporting planted omissions on every build; the tool list and
 * the declaration the service serves are checked by {@link #omissions} from
 * {@code McpProtocolIT}, which reads them from {@code tools/list} and {@code
 * GET /api/declaration}, as {@link DeclarationShapeTest} is run from {@code
 * DeclarationAccessIT}.
 */
public class ToolSchemaProjectionTest {

    /**
     * Every declared value a tool schema leaves out of the {@code enum} it
     * carries for the argument, one line per argument; empty where every
     * enumeration is complete.
     *
     * @param declaration the declaration as served, {@code calls} with their
     *                    {@code arguments}
     * @param tools       the {@code tools} of {@code tools/list}, each with
     *                    {@code name} and {@code inputSchema}
     */
    @SuppressWarnings("unchecked")
    public static List<String> omissions(Map<String, Object> declaration,
                                         List<Map<String, Object>> tools) {
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        for (Map<String, Object> tool : tools) {
            schemas.put(String.valueOf(tool.get("name")),
                (Map<String, Object>) tool.get("inputSchema"));
        }
        List<String> found = new ArrayList<>();
        for (Map<String, Object> call : (List<Map<String, Object>>) declaration.get("calls")) {
            String name = String.valueOf(call.get("call"));
            for (Map<String, Object> argument
                    : (List<Map<String, Object>>) call.get("arguments")) {
                if (!(argument.get("values") instanceof List<?> declared)) {
                    continue;
                }
                boolean underFields = "FIELDS".equals(argument.get("placement"));
                String path = (underFields ? "fields." : "") + argument.get("name");
                Map<String, Object> property = property(schemas.get(name),
                    String.valueOf(argument.get("name")), underFields);
                if (property == null) {
                    found.add(name + " " + path + ": declares " + declared
                        + " and is absent from the tool schema");
                    continue;
                }
                if (!(property.get("enum") instanceof List<?> enumerated)) {
                    continue;
                }
                for (Object value : declared) {
                    if (!enumerated.contains(value)) {
                        found.add(name + " " + path + ": enumerates " + enumerated
                            + " and leaves out the declared '" + value + "'");
                    }
                }
            }
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> schema, String argument,
                                                boolean underFields) {
        if (schema == null) {
            return null;
        }
        Map<String, Object> level = schema;
        if (underFields) {
            Object fields = ((Map<String, Object>) schema.getOrDefault("properties", Map.of()))
                .get("fields");
            if (!(fields instanceof Map<?, ?>)) {
                return null;
            }
            level = (Map<String, Object>) fields;
        }
        Object found = ((Map<String, Object>) level.getOrDefault("properties", Map.of()))
            .get(argument);
        return found instanceof Map<?, ?> ? (Map<String, Object>) found : null;
    }

    @Test
    void a_tool_list_that_enumerates_every_declared_value_or_leaves_it_open_passes() {
        assertThat(omissions(declaration(), List.of(tool(
                property("sort", List.of("id", "created_at")),
                fields(property("part", List.of("dispatch", "return"))),
                Map.of("name", "direction")))))
            .as("complete enumerations, and an argument left open, are a projection")
            .isEmpty();
    }

    /** The red state, observed on every build: each kind of omission is reported. */
    @Test
    void the_checks_report_each_offence_they_exist_for() {
        assertThat(omissions(declaration(), List.of(tool(
                property("sort", List.of("id")),
                fields(property("part", List.of("dispatch", "return"))),
                Map.of("name", "direction")))))
            .as("RED STATE, observed: a top-level enumeration narrower than the declaration")
            .containsExactly("thing_list sort: enumerates [id] and leaves out the declared "
                + "'created_at'");

        assertThat(omissions(declaration(), List.of(tool(
                property("sort", List.of("id", "created_at")),
                fields(property("part", List.of("dispatch"))),
                Map.of("name", "direction")))))
            .as("RED STATE, observed: an enumeration under fields narrower than the declaration")
            .containsExactly("thing_list fields.part: enumerates [dispatch] and leaves out the "
                + "declared 'return'");

        assertThat(omissions(declaration(), List.of(tool(
                property("sort", List.of("id", "created_at")),
                fields(Map.of("name", "text"))))))
            .as("RED STATE, observed: a declared argument the tool schema does not carry")
            .containsExactly("thing_list fields.part: declares [dispatch, return] and is "
                + "absent from the tool schema");
    }

    // ======================================================================
    // A declaration and a tool list, built here for the planted cases
    // ======================================================================

    private static Map<String, Object> declaration() {
        return Map.of("calls", List.of(Map.of("call", "thing_list", "arguments", List.of(
            argument("sort", "TOP", List.of("id", "created_at")),
            argument("part", "FIELDS", List.of("dispatch", "return")),
            Map.of("name", "direction", "placement", "TOP")))));
    }

    private static Map<String, Object> argument(String name, String placement,
                                                List<String> values) {
        return Map.of("name", name, "placement", placement, "values", values);
    }

    /** A property with an enumeration, keyed by name for {@link #tool}. */
    private static Map<String, Object> property(String name, List<String> enumerated) {
        return Map.of("name", name, "enum", enumerated);
    }

    /** The {@code fields} object holding the given properties. */
    private static Map<String, Object> fields(Map<String, Object> inner) {
        return Map.of("name", "fields", "type", "object",
            "properties", Map.of(String.valueOf(inner.get("name")), inner));
    }

    @SafeVarargs
    private static Map<String, Object> tool(Map<String, Object>... properties) {
        Map<String, Object> top = new LinkedHashMap<>();
        for (Map<String, Object> property : properties) {
            top.put(String.valueOf(property.get("name")), property);
        }
        return Map.of("name", "thing_list",
            "inputSchema", Map.of("type", "object", "properties", top));
    }
}
