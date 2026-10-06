package ai.kumbuka.dispatch.adapter.mcp;

import ai.kumbuka.dispatch.surface.Argument;
import ai.kumbuka.dispatch.surface.CallArguments;
import ai.kumbuka.dispatch.surface.ProcessVerb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The twenty-five tools of the assistant surface, generated from the
 * declaration.
 *
 * <p><strong>Generated, not written.</strong> Names, descriptions and schemas
 * are a projection of {@link ProcessVerb} into the shape {@code tools/list}
 * asks for. The tests that hold this list take their expected values from the
 * target and the concept document, written into the tests and the contract
 * copy by hand, so generating here removes a copy without making those tests
 * circular.
 *
 * <h2>Two levels, both closed</h2>
 *
 * The schema has an inner {@code fields} object wherever the call writes
 * anything, and {@code additionalProperties: false} stands on both. The value
 * of {@code metadata} is the caller's own keys and is an object with no
 * declared members; it is a value under a closed level, not a level.
 */
public final class McpTools {

    /** The JSON Schema member every argument carries, by its schema name. */
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_TYPE = "type";
    private static final String OBJECT = "object";

    private McpTools() {
    }

    /** One declared tool: its name, what it does, and what it takes. */
    public record Tool(String name, String description, Map<String, Object> inputSchema) {
    }

    /** The twenty-five, in the order of the declaration. */
    public static List<Tool> declared() {
        return Arrays.stream(ProcessVerb.values())
            .map(verb -> new Tool(verb.call(), verb.description(), schemaOf(verb)))
            .toList();
    }

    /** The input schema of one call: top-level arguments, plus {@code fields} where it writes. */
    private static Map<String, Object> schemaOf(ProcessVerb verb) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Argument argument : verb.topArguments()) {
            properties.put(argument.name(), property(argument));
            if (argument.required()) {
                required.add(argument.name());
            }
        }

        if (verb.hasFields()) {
            properties.put(CallArguments.FIELDS, fieldsSchema(verb));
            if (verb.fieldArguments().stream().anyMatch(Argument::required)) {
                required.add(CallArguments.FIELDS);
            }
        }

        return closedObject(properties, required);
    }

    /** The inner object: everything the call writes into the task, closed in its own right. */
    private static Map<String, Object> fieldsSchema(ProcessVerb verb) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Argument argument : verb.fieldArguments()) {
            properties.put(argument.name(), property(argument));
            if (argument.required()) {
                required.add(argument.name());
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>(closedObject(properties, required));
        schema.put(KEY_DESCRIPTION,
            "What this call writes into the task. Values live here; the arguments that "
                + "name the target live beside it.");
        return Map.copyOf(schema);
    }

    /**
     * One argument's schema: its type and description, the closed set of its
     * values where it has one, and for a list the rule every element obeys —
     * the same rule the surface enforces, read from the declaration.
     */
    private static Map<String, Object> property(Argument argument) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put(KEY_TYPE, argument.type());
        if (Argument.ARRAY.equals(argument.type())) {
            Map<String, Object> items = new LinkedHashMap<>();
            items.put(KEY_TYPE, Argument.STRING);
            if (argument.itemPattern() != null) {
                items.put("pattern", argument.itemPattern());
            }
            property.put("items", Map.copyOf(items));
            if (argument.required()) {
                property.put("minItems", 1);
            }
        }
        if (!argument.values().isEmpty()) {
            property.put("enum", argument.values());
        }
        property.put(KEY_DESCRIPTION, argument.description());
        return Map.copyOf(property);
    }

    /** A JSON Schema object with {@code additionalProperties} closed. */
    private static Map<String, Object> closedObject(Map<String, Object> properties,
                                                    List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(KEY_TYPE, OBJECT);
        schema.put("properties", Map.copyOf(properties));
        schema.put("required", List.copyOf(required));
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }
}
