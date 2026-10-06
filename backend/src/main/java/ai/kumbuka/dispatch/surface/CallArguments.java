package ai.kumbuka.dispatch.surface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One call's arguments, checked against the declaration before anything is
 * read out of them.
 *
 * <p><strong>Checked first and completely.</strong> The constructor walks both
 * levels of the incoming map, refuses the first argument the call does not
 * declare, then the first one of the wrong type or outside its values, then
 * the first required one that is absent. Nothing is resolved, read or written
 * before that walk finishes, which is what "nothing was written" in an
 * argument refusal is a statement about.
 *
 * <p>Both surfaces build the same map — MCP from the tool call's arguments,
 * REST from the path, the {@code If-Match} header, the query and the body — so
 * a call is closed against one declaration whichever way it arrived. A schema
 * is a description; this is the enforcement.
 */
public final class CallArguments {

    /** The one nested object a call may carry, by the name DEC-0040 gives it. */
    public static final String FIELDS = "fields";

    private final ProcessVerb verb;
    private final String call;
    private final Map<String, Object> top;
    private final Map<String, Object> fields;

    /**
     * Reads the arguments of one call, refusing anything the declaration does
     * not admit.
     *
     * @param call the call's name as the caller made it, for the refusal
     * @throws Refused {@code ARGUMENT_UNKNOWN}, {@code ARGUMENT_INVALID} or
     *                 {@code ARGUMENT_MISSING}, naming the argument
     */
    public CallArguments(ProcessVerb verb, String call, Map<String, Object> arguments) {
        this.verb = verb;
        this.call = call;
        this.top = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
        this.fields = nestedFields();

        refuseUndeclared();
        refuseMistyped();
        refuseMissing();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedFields() {
        Object nested = top.get(FIELDS);
        if (nested == null || !verb.hasFields()) {
            return Map.of();
        }
        if (!(nested instanceof Map)) {
            throw Refused.argumentInvalid(call, FIELDS, String.valueOf(nested),
                "it carries the values this call writes, and is an object");
        }
        Map<String, Object> carried = new LinkedHashMap<>();
        ((Map<Object, Object>) nested).forEach((k, v) -> carried.put(String.valueOf(k), v));
        return carried;
    }

    private void refuseUndeclared() {
        List<String> declared = verb.topNames();
        for (String name : top.keySet()) {
            if (!declared.contains(name)) {
                throw Refused.argumentUnknown(call, call, "argument", name, declared);
            }
        }
        List<String> declaredFields = verb.fieldArguments().stream().map(Argument::name).toList();
        for (String name : fields.keySet()) {
            if (!declaredFields.contains(name)) {
                throw Refused.argumentUnknown(call, call, "argument under fields", name,
                    declaredFields);
            }
        }
    }

    private void refuseMistyped() {
        for (Argument argument : verb.arguments()) {
            Object value = levelOf(argument).get(argument.name());
            if (value == null) {
                continue;
            }
            String why = mistyped(argument, value);
            if (why != null) {
                throw Refused.argumentInvalid(call, argument.name(), String.valueOf(value), why);
            }
        }
    }

    private static String mistyped(Argument argument, Object value) {
        return switch (argument.type()) {
            case Argument.STRING -> {
                if (value instanceof Map || value instanceof List) {
                    yield "it is a string";
                }
                if (!argument.values().isEmpty()
                        && !argument.values().contains(String.valueOf(value))) {
                    yield "it is one of " + String.join(", ", argument.values());
                }
                yield null;
            }
            case Argument.ARRAY -> value instanceof List<?> list
                    && list.stream().allMatch(e -> e instanceof String)
                ? null
                : "it is a list of strings, even when it carries one value";
            case Argument.OBJECT -> value instanceof Map ? null : "it is an object";
            case Argument.BOOLEAN -> value instanceof Boolean
                    || "true".equals(value) || "false".equals(value)
                ? null
                : "it is true or false";
            case Argument.INTEGER -> integerOf(value) != null ? null : "it is a whole number";
            default -> "its declared type is " + argument.type();
        };
    }

    private void refuseMissing() {
        for (Argument argument : verb.arguments()) {
            if (!argument.required()) {
                continue;
            }
            Object value = levelOf(argument).get(argument.name());
            boolean absent = value == null
                || (value instanceof String s && s.isBlank())
                || (value instanceof List<?> l && l.isEmpty());
            if (absent) {
                throw Refused.argumentMissing(call, argument.isField()
                    ? FIELDS + "." + argument.name()
                    : argument.name(), argument.description());
            }
        }
    }

    private Map<String, Object> levelOf(Argument argument) {
        return argument.isField() ? fields : top;
    }

    // ======================================================================
    // Reading values. Every one of them has passed the three checks above.
    // ======================================================================

    /** The call's name as the caller made it. */
    public String call() {
        return call;
    }

    public ProcessVerb verb() {
        return verb;
    }

    /** A top-level string, or null where it is absent. */
    public String top(String name) {
        return string(top.get(name));
    }

    /** A string under {@code fields}, or null where it is absent. */
    public String field(String name) {
        return string(fields.get(name));
    }

    /** A list of strings at either level, empty where it is absent. */
    public List<String> list(String name, Argument.Placement placement) {
        Object raw = (placement == Argument.Placement.FIELDS ? fields : top).get(name);
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    /** A yes or no under {@code fields}, false where it is absent. */
    public boolean flag(String name) {
        Object raw = fields.get(name);
        return Boolean.TRUE.equals(raw) || "true".equals(raw);
    }

    /** A whole number at the top level, or null where it is absent. */
    public Integer integer(String name) {
        return integerOf(top.get(name));
    }

    /** Whether a value under {@code fields} was given at all. */
    public boolean hasField(String name) {
        return fields.containsKey(name) && fields.get(name) != null;
    }

    /** The metadata object under {@code fields}, or null where it is absent. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> metadata() {
        Object raw = fields.get("metadata");
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<String, Object> carried = new LinkedHashMap<>();
        ((Map<Object, Object>) raw).forEach((k, v) -> carried.put(String.valueOf(k), v));
        return carried;
    }

    /** The top-level arguments of a listing's filters, as the kernel reads them. */
    public Map<String, String> filters(List<String> names) {
        Map<String, String> filters = new LinkedHashMap<>();
        for (String name : names) {
            String value = top(name);
            if (value != null) {
                filters.put(name, value);
            }
        }
        return filters;
    }

    /** Every argument name that was given, at the top level. */
    public List<String> givenTop() {
        return new ArrayList<>(top.keySet());
    }

    private static Integer integerOf(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
