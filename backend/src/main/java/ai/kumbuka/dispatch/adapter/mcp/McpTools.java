package ai.kumbuka.dispatch.adapter.mcp;

import ai.kumbuka.dispatch.surface.Argument;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.domain.QueryFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fourteen tools of the assistant surface, generated from the declaration.
 *
 * <p><strong>Generated, not written.</strong> This file used to carry
 * hand-written names, descriptions and schemas beside a domain that carried
 * the same facts — and the two drifted in the way two copies do: the schema
 * here was closed while the router's was open, which is the path by which
 * {@code draft} reached the service and was silently dropped. The declaration
 * is now the one place, and this is a projection of it into the shape
 * {@code tools/list} asks for.
 *
 * <p>That does make the earlier reasoning here obsolete: this class argued
 * that writing the list out by hand was what kept the two expositions
 * share-nothing, and that a generated list would make the conformance probe
 * "an assertion that one copy equals itself". The argument was right about the
 * risk and wrong about where the second copy was. The probe now takes its
 * expected values from the CONTRACT DOCUMENT — a third thing, which neither
 * the declaration nor this class can edit — so generating from the declaration
 * removes a copy without making the probe circular.
 *
 * <h2>Two levels, both closed</h2>
 *
 * The schema has an inner {@code fields} object wherever the call writes
 * anything, and {@code additionalProperties: false} stands on both. One level
 * of closure is what the measured surface had, and it is exactly as much use
 * as none: {@code draft} was rejected nowhere because nothing looked inside.
 */
public final class McpTools {

    private McpTools() {
    }

    /** One declared tool: its name, what it does, and what it takes. */
    public record Tool(String name, String description, Map<String, Object> inputSchema) {
    }

    private static final String OBJECT = "object";

    /** The fourteen, in the order of section 5 of the contract. */
    public static List<Tool> declared() {
        List<Tool> tools = new ArrayList<>();
        for (ProcessVerb verb : ProcessVerb.values()) {
            tools.add(new Tool(verb.call(), verb.description(), schemaOf(verb)));
        }
        return List.copyOf(tools);
    }

    /**
     * The input schema of one call: top-level arguments, plus {@code fields}
     * where the call writes.
     *
     * <p>{@code dispatch_query} is the one call whose schema is not wholly from
     * the declaration: its filters are the DOMAIN's, read from {@link
     * QueryFilter.Field}. Declaring them a second time here is how a schema
     * comes to advertise a filter the domain refuses, or hide one it accepts.
     */
    private static Map<String, Object> schemaOf(ProcessVerb verb) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Argument argument : verb.topArguments()) {
            properties.put(argument.name(), property(argument));
            if (argument.required()) {
                required.add(argument.name());
            }
        }

        if (verb == ProcessVerb.QUERY) {
            for (QueryFilter.Field field : QueryFilter.Field.values()) {
                properties.put(field.wireName(), Map.of(
                    "type", "string",
                    "description", "Narrow by " + field.wireName()
                        + ". Comma-separated values are read as alternatives."));
            }
        }

        if (verb.hasFields()) {
            properties.put("fields", fieldsSchema(verb));
            if (verb.fieldArguments().stream().anyMatch(Argument::required)) {
                required.add("fields");
            }
        }

        return closedObject(properties, required);
    }

    /**
     * The inner object: everything the call writes into the exchange.
     *
     * <p>Closed in its own right. That is the half the measured surface was
     * missing, and it is not a smaller omission than the outer one — a caller
     * that misspells a value it is writing loses the value, whereas one that
     * misspells a target argument usually gets a refusal from the lookup.
     */
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
        schema.put("description",
            "What this call writes into the exchange. Values live here; the arguments that "
                + "choose the target live beside it.");
        return Map.copyOf(schema);
    }

    private static Map<String, Object> property(Argument argument) {
        return Map.of("type", argument.type(), "description", argument.description());
    }

    /**
     * A JSON Schema object with {@code additionalProperties} closed.
     *
     * <p>Closed rather than open, deliberately: an argument this surface does
     * not know is one a caller believes in. Accepting and ignoring it is how a
     * client comes to depend on a field the server never read — and, measured
     * on 2026-09-18, how a commission came to be created with an empty body
     * while its author was told it had succeeded.
     */
    private static Map<String, Object> closedObject(Map<String, Object> properties,
                                                    List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", OBJECT);
        schema.put("properties", Map.copyOf(properties));
        schema.put("required", List.copyOf(required));
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }
}
