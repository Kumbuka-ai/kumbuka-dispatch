package ai.kumbuka.dispatch.adapter.mcp;

import ai.kumbuka.dispatch.surface.Argument;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Refused;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.domain.QueryFilter;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One tool call's arguments, checked against the declaration before anything
 * is read out of them.
 *
 * <p><strong>Checked first and completely.</strong> The constructor walks both
 * levels of the incoming map and refuses the first argument the call does not
 * declare. Nothing is read, resolved or written before that walk finishes,
 * which is what "nothing was written" in the {@code ARGUMENT_UNKNOWN} refusal
 * is a statement about — not a promise the caller has to take on trust, but a
 * property of when the check runs.
 *
 * <p>The schema published in {@code tools/list} says the same thing, and a
 * conforming client would never send an undeclared argument. This class exists
 * because clients are not all conforming and because the router in front of
 * this service publishes its own schema — which, measured on 2026-09-18, had
 * {@code additionalProperties: true} on the very body that carried the
 * discarded argument. A schema is a description; this is the enforcement.
 */
final class CallArguments {

    private final ProcessVerb verb;
    private final Map<String, Object> top;
    private final Map<String, Object> fields;

    /**
     * Reads the arguments of one call, refusing anything undeclared.
     *
     * @throws Refused with {@code ARGUMENT_UNKNOWN} on the first argument the
     *                 call does not declare, at either level
     */
    CallArguments(ProcessVerb verb, Map<String, Object> arguments) {
        this.verb = verb;
        this.top = arguments == null ? Map.of() : arguments;
        this.fields = nestedFields(verb, this.top);

        refuseUndeclaredTop();
        refuseUndeclaredFields();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedFields(ProcessVerb verb,
                                                    Map<String, Object> arguments) {
        Object nested = arguments.get("fields");
        if (nested == null) {
            return Map.of();
        }
        if (!(nested instanceof Map)) {
            throw Refused.argumentInvalid(Surface.MCP, verb.call(), "fields", String.valueOf(nested),
                "it carries the values this call writes and is an object");
        }
        return (Map<String, Object>) nested;
    }

    /**
     * The top level.
     *
     * <p>{@code dispatch_query}'s declared filter fields are the domain's, so
     * they are admitted by asking {@link QueryFilter.Field} rather than by
     * being listed a second time here. A filter the domain does not declare
     * still refuses — one level down, with the domain's own reason — and that
     * is the right place for it: the filterable fields are a property of the
     * store, not of this adapter.
     */
    private void refuseUndeclaredTop() {
        List<String> declared = new java.util.ArrayList<>(
            verb.topArguments().stream().map(Argument::name).toList());
        if (verb.hasFields()) {
            declared.add("fields");
        }
        if (verb == ProcessVerb.QUERY) {
            for (QueryFilter.Field field : QueryFilter.Field.values()) {
                declared.add(field.wireName());
            }
        }

        for (String name : top.keySet()) {
            if (!declared.contains(name)) {
                throw Refused.argumentUnknown(Surface.MCP, verb.call(), name, declared);
            }
        }
    }

    /** The inner level, closed the same way and for the same reason. */
    private void refuseUndeclaredFields() {
        List<String> declared = verb.fieldArguments().stream().map(Argument::name).toList();
        for (String name : fields.keySet()) {
            if (!declared.contains(name)) {
                throw Refused.argumentUnknown(Surface.MCP, verb.call(), name,
                    declared.isEmpty() ? List.of("none: this call writes nothing") : declared);
            }
        }
    }

    // ======================================================================
    // Reading values
    // ======================================================================

    /** A required top-level string. */
    String requiredTop(String name) {
        return require(name, string(top.get(name)));
    }

    /** An optional top-level string, or null. */
    String optionalTop(String name) {
        return string(top.get(name));
    }

    /** A required value under {@code fields}. */
    String requiredField(String name) {
        return require(name, string(fields.get(name)));
    }

    /** An optional value under {@code fields}, or null. */
    String optionalField(String name) {
        return string(fields.get(name));
    }

    /**
     * An optional date under {@code fields}, defaulting where the declaration
     * says it may.
     *
     * <p>{@code dispatch_commission} declares "today if omitted", so the
     * default lives with the declaration rather than in the domain — the
     * domain has no business deciding that an absent date means now.
     */
    LocalDate optionalDateField(String name, LocalDate fallback) {
        String raw = optionalField(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw Refused.argumentInvalid(Surface.MCP, verb.call(), name, raw,
                "a date is written YYYY-MM-DD");
        }
    }

    /**
     * Metadata under {@code fields}, carried through as it arrived.
     *
     * <p>Not coerced. An earlier shape flattened every value through {@code
     * toString} and would have turned a real list into the prose "[a, b]";
     * the domain's validator knows the accepted shapes and refuses the rest.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> metadataField() {
        Object raw = fields.get("metadata");
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<String, Object> carried = new LinkedHashMap<>();
        ((Map<Object, Object>) raw).forEach((k, v) -> carried.put(String.valueOf(k), v));
        return carried;
    }

    /** The filters of a listing: everything that is not scope or selector. */
    Map<String, String> filters() {
        Map<String, String> filters = new LinkedHashMap<>();
        top.forEach((name, value) -> {
            if (!"scope".equals(name) && !"selector".equals(name) && value != null) {
                filters.put(name, String.valueOf(value));
            }
        });
        return filters;
    }

    private String require(String name, String value) {
        if (value == null || value.isBlank()) {
            Argument declared = verb.argument(name);
            throw Refused.argumentMissing(Surface.MCP, verb.call(), name,
                declared == null ? "an argument of this call" : declared.description());
        }
        return value;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
