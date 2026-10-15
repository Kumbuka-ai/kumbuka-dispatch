/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
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
 * <p><strong>{@code null} is absent</strong> (DEC-0043 R17). An argument sent
 * as JSON {@code null} is dropped before anything is checked, at the top and
 * under {@code fields}, and {@code fields} sent as {@code null} is no {@code
 * fields}: an optional argument then takes its declared fallback, a required
 * one is refused as missing, and a name the call does not declare is not
 * refused at all, exactly as if the caller had left it out.
 *
 * <p>Both surfaces build the same map — MCP from the tool call's arguments,
 * REST from the path, the {@code If-Match} header, the query and the body — so
 * a call is closed against one declaration whichever way it arrived. A schema
 * is a description; this is the enforcement.
 */
public final class CallArguments {

    /** The one nested object a call may carry, by the name DEC-0040 gives it. */
    public static final String FIELDS = "fields";

    /** The page bound of a listing: an argument whose value refusal has its own code. */
    public static final String PAGE_SIZE = "page_size";

    /** The order of a listing: an argument whose value refusal has its own code. */
    public static final String SORT = "sort";

    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);
    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final Pattern WHOLE_TEXT = Pattern.compile("[+-]?\\d+");
    /** Every run of this many digits fits a long; one more may not. */
    private static final int MAX_LONG_DIGITS = 18;

    private final ProcessVerb verb;
    private final VerbName call;
    private final Map<String, Object> top;
    private final Map<String, Object> written;

    /**
     * Reads the arguments of one call, refusing anything the declaration does
     * not admit.
     *
     * @param surface the surface the call arrived on, which names it in a
     *                refusal
     * @throws Refused {@code ARGUMENT_UNKNOWN}, {@code ARGUMENT_INVALID} or
     *                 {@code ARGUMENT_MISSING}, naming the argument; {@code
     *                 PAGE_SIZE_REJECTED} for a page size that is no whole
     *                 number, {@code SORT_UNDECLARED} for a sort the listing
     *                 does not declare
     */
    public CallArguments(ProcessVerb verb, Surface surface, Map<String, Object> arguments) {
        this.verb = verb;
        this.call = VerbName.of(verb, surface);
        this.top = present(arguments);
        this.written = nestedFields();

        refuseUndeclared();
        refuseMistyped();
        refuseMissing();
    }

    /** The members of one level that carry a value: a {@code null} is a member not sent. */
    private static Map<String, Object> present(Map<?, ?> level) {
        Map<String, Object> carried = new LinkedHashMap<>();
        if (level != null) {
            level.forEach((name, value) -> {
                if (value != null) {
                    carried.put(String.valueOf(name), value);
                }
            });
        }
        return carried;
    }

    private Map<String, Object> nestedFields() {
        Object nested = top.get(FIELDS);
        if (nested == null || !verb.hasFields()) {
            return Map.of();
        }
        if (!(nested instanceof Map<?, ?> map)) {
            throw Refused.argumentInvalid(call, ArgumentPath.FIELDS, Form.FIELDS_OBJECT);
        }
        return present(map);
    }

    private void refuseUndeclared() {
        List<ArgumentPath> topLevel = new java.util.ArrayList<>(
            ArgumentPath.of(verb.topArguments()));
        if (verb.hasFields()) {
            topLevel.add(ArgumentPath.FIELDS);
        }
        refuseUndeclared(top, verb.topNames(), false, topLevel);
        refuseUndeclared(written, verb.fieldArguments().stream().map(Argument::name).toList(),
            true, ArgumentPath.of(verb.fieldArguments()));
    }

    /**
     * Refuses a level that carries a name the declaration does not hold. The
     * refusal names none of them (DEC-0043 R19): it lists what the level
     * accepts.
     */
    private void refuseUndeclared(Map<String, Object> level, List<String> declared,
                                  boolean underFields, List<ArgumentPath> accepts) {
        boolean undeclared = level.keySet().stream().anyMatch(name -> !declared.contains(name));
        if (undeclared) {
            throw Refused.argumentUnknown(call, underFields, accepts);
        }
    }

    private void refuseMistyped() {
        for (Argument argument : verb.arguments()) {
            Object value = levelOf(argument).get(argument.name());
            if (value == null) {
                continue;
            }
            Form form = mistyped(argument, value);
            if (form == null) {
                continue;
            }
            if (PAGE_SIZE.equals(argument.name())) {
                // ADR-0049: every page size a listing cannot take is refused
                // as one, a value that is no whole number as much as one out
                // of bounds, so a caller correcting it branches on one code.
                throw Refused.pageSizeRejected(call, ArgumentPath.of(argument));
            }
            if (SORT.equals(argument.name()) && form == Form.ONE_OF) {
                // ADR-0049: a sort the listing does not declare has its own code.
                throw Refused.sortUndeclared(call, ArgumentPath.of(argument));
            }
            throw Refused.argumentInvalid(call, positionOf(argument, value), form);
        }
    }

    private static Form mistyped(Argument argument, Object value) {
        return switch (argument.type()) {
            case Argument.STRING -> {
                if (value instanceof Map || value instanceof List) {
                    yield Form.STRING;
                }
                if (!argument.values().isEmpty()
                        && !argument.valueNames().contains(String.valueOf(value))) {
                    yield Form.ONE_OF;
                }
                yield null;
            }
            case Argument.ARRAY -> value instanceof List<?> list
                    && list.stream().allMatch(String.class::isInstance)
                ? null
                : Form.LIST_OF_STRINGS;
            case Argument.OBJECT -> value instanceof Map ? null : Form.OBJECT;
            case Argument.BOOLEAN -> value instanceof Boolean
                    || "true".equals(value) || "false".equals(value)
                ? null
                : Form.BOOLEAN;
            // Whether the number is one the call can take is the call's own
            // refusal: a page size outside its bounds is refused as one, and
            // so is one that is no whole number (refuseMistyped).
            case Argument.INTEGER -> wholeOf(value) == null ? Form.WHOLE_NUMBER : null;
            default -> throw new IllegalStateException(
                argument.name() + " is declared with a type the surface does not read");
        };
    }

    /**
     * Where a refused value stands: the argument, and for a list the position
     * of the first element that is no string (DEC-0043 R19).
     */
    private static ArgumentPath positionOf(Argument argument, Object value) {
        if (Argument.ARRAY.equals(argument.type()) && value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof String)) {
                    return ArgumentPath.of(argument, i);
                }
            }
        }
        return ArgumentPath.of(argument);
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
                throw Refused.argumentMissing(call, ArgumentPath.of(argument));
            }
        }
    }

    private Map<String, Object> levelOf(Argument argument) {
        return argument.isField() ? written : top;
    }

    // ======================================================================
    // Reading values. Every one of them has passed the three checks above.
    // ======================================================================

    /** The call's name on the surface it arrived on, as a refusal names it. */
    public VerbName call() {
        return call;
    }

    /** The declared argument of this name at one level; a defect where there is none. */
    public Argument declared(String name, Argument.Placement placement) {
        Argument argument = verb.argument(name, placement);
        if (argument == null) {
            throw new IllegalStateException(verb.call() + " declares no " + name);
        }
        return argument;
    }

    /** The path of the declared top-level argument of this name. */
    public ArgumentPath topPath(String name) {
        return ArgumentPath.of(declared(name, Argument.Placement.TOP));
    }

    /** The path of the declared argument of this name under {@code fields}. */
    public ArgumentPath fieldPath(String name) {
        return ArgumentPath.of(declared(name, Argument.Placement.FIELDS));
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

    /**
     * A whole number at the top level, or null where it is absent. A number
     * past what a long holds arrives as the long's bound on its side.
     */
    public Long wholeNumber(String name) {
        return wholeOf(top.get(name));
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

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
