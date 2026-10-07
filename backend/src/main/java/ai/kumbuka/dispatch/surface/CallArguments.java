package ai.kumbuka.dispatch.surface;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One call's arguments, checked against the declaration before anything is
 * read out of them.
 *
 * <p><strong>Checked first and completely.</strong> The constructor walks both
 * levels of the incoming map and refuses, in this order: the arguments the
 * call does not declare, then the first one of the wrong type or outside its
 * values, then the first required one that is absent. Undeclared arguments are
 * refused one level at a time and all of a level together: every undeclared
 * name at the top in one refusal, and only when the top is clean every
 * undeclared name under {@code fields} in one, each in the order the call
 * carried them. Nothing is resolved, read or written
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

    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);
    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final Pattern WHOLE_TEXT = Pattern.compile("[+-]?\\d+");
    /** Every run of this many digits fits a long; one more may not. */
    private static final int MAX_LONG_DIGITS = 18;

    private final ProcessVerb verb;
    private final String call;
    private final Map<String, Object> top;
    private final Map<String, Object> written;

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
        this.written = nestedFields();

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
        refuseUndeclared(top, verb.topNames(), "argument");
        refuseUndeclared(written, verb.fieldArguments().stream().map(Argument::name).toList(),
            "argument under fields");
    }

    /** Refuses every name of one level the declaration does not hold, in one refusal. */
    private void refuseUndeclared(Map<String, Object> level, List<String> declared, String kind) {
        List<String> undeclared = level.keySet().stream()
            .filter(name -> !declared.contains(name))
            .toList();
        if (!undeclared.isEmpty()) {
            throw Refused.argumentUnknown(call, call, kind, String.join(", ", undeclared),
                declared);
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
                    && list.stream().allMatch(String.class::isInstance)
                ? null
                : "it is a list of strings, even when it carries one value";
            case Argument.OBJECT -> value instanceof Map ? null : "it is an object";
            case Argument.BOOLEAN -> value instanceof Boolean
                    || "true".equals(value) || "false".equals(value)
                ? null
                : "it is true or false";
            case Argument.INTEGER -> {
                Long whole = wholeOf(value);
                if (whole == null) {
                    yield "it is a whole number";
                }
                yield fitsAnInt(whole)
                    ? null
                    : "it is too large for a whole number here, which lies from "
                        + Integer.MIN_VALUE + " to " + Integer.MAX_VALUE;
            }
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
        return argument.isField() ? written : top;
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
        return string(written.get(name));
    }

    /** A list of strings at either level, empty where it is absent. */
    public List<String> list(String name, Argument.Placement placement) {
        Object raw = (placement == Argument.Placement.FIELDS ? written : top).get(name);
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    /** A yes or no under {@code fields}, false where it is absent. */
    public boolean flag(String name) {
        Object raw = written.get(name);
        return Boolean.TRUE.equals(raw) || "true".equals(raw);
    }

    /** A whole number at the top level, or null where it is absent. */
    public Integer integer(String name) {
        return integerOf(top.get(name));
    }

    /** The metadata object under {@code fields}, or empty where it is absent. */
    @SuppressWarnings("unchecked")
    public java.util.Optional<Map<String, Object>> metadata() {
        Object raw = written.get("metadata");
        if (!(raw instanceof Map)) {
            return java.util.Optional.empty();
        }
        Map<String, Object> carried = new LinkedHashMap<>();
        ((Map<Object, Object>) raw).forEach((k, v) -> carried.put(String.valueOf(k), v));
        return java.util.Optional.of(carried);
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

    /** A whole number that fits an int, or null where the value is none. */
    private static Integer integerOf(Object value) {
        Long whole = wholeOf(value);
        return whole != null && fitsAnInt(whole) ? whole.intValue() : null;
    }

    /**
     * The whole number a value names, or null where it names none: a JSON
     * number with no fraction, or text that is a whole number. A number past
     * what a long holds comes back as the long's bound on its side, which is
     * enough to refuse it as too large without ever building it.
     */
    private static Long wholeOf(Object value) {
        if (value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof String s) {
            return wholeOf(s.trim());
        }
        if (!(value instanceof Number n)) {
            return null;
        }
        BigDecimal exact;
        if (n instanceof BigDecimal d) {
            exact = d;
        } else if (n instanceof BigInteger b) {
            exact = new BigDecimal(b);
        } else if (Double.isFinite(n.doubleValue())) {
            exact = BigDecimal.valueOf(n.doubleValue());
        } else {
            return null;
        }
        if (exact.signum() != 0 && exact.stripTrailingZeros().scale() > 0) {
            return null;
        }
        if (exact.compareTo(LONG_MAX) > 0) {
            return Long.MAX_VALUE;
        }
        if (exact.compareTo(LONG_MIN) < 0) {
            return Long.MIN_VALUE;
        }
        return exact.longValueExact();
    }

    /** Text that is a sign and digits; more than eighteen digits are read as a bound. */
    private static Long wholeOf(String text) {
        if (!WHOLE_TEXT.matcher(text).matches()) {
            return null;
        }
        boolean negative = text.startsWith("-");
        String digits = text.replaceFirst("^[+-]?0*", "");
        if (digits.length() > MAX_LONG_DIGITS) {
            return negative ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        long magnitude = digits.isEmpty() ? 0 : Long.parseLong(digits);
        return negative ? -magnitude : magnitude;
    }

    private static boolean fitsAnInt(long whole) {
        return whole >= Integer.MIN_VALUE && whole <= Integer.MAX_VALUE;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
