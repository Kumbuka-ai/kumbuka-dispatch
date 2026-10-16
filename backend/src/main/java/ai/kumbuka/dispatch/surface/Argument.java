/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import java.util.List;
import java.util.function.Supplier;

/**
 * One argument of one call, and where it travels.
 *
 * <p>The placement is the part that is easy to read as decoration and is not.
 * DEC-0040 puts what the call <em>writes</em> under {@code fields} and what
 * chooses the target or carries the transport at the top level (concept
 * section 3.1). The split is what lets a caller see, from the shape of its own
 * call, which half of it lands in the task — and it is what lets the schema be
 * closed at both levels instead of only the outer one, which is how {@code
 * draft} once reached the service and was dropped.
 *
 * @param name        the argument's name on the wire
 * @param type        its JSON Schema type
 * @param required    whether the call refuses without it
 * @param placement   top level, or nested under {@code fields}
 * @param text        what it is: a reference to its text in {@code
 *                    messages/argument.properties}, read when the text is
 *                    needed and never when the declaration is built
 * @param itemPattern for an {@link #ARRAY} argument, the regular expression
 *                    every element obeys, or null. It travels into the
 *                    published schema so a conforming client can refuse a
 *                    malformed element before sending it; the surface enforces
 *                    the same rule
 * @param values      the closed set of values a {@link #STRING} argument takes,
 *                    each with what it means, or empty where it takes any
 *                    string. Published as the schema's {@code enum} and
 *                    enforced by the surface
 * @param fallback    the value an optional argument takes when it is left out
 *                    or sent as {@code null} (DEC-0043 R17), as the
 *                    declaration publishes it; null for a required argument
 */
public record Argument(String name, String type, boolean required, Placement placement,
                       Supplier<String> text, String itemPattern, List<Value> values,
                       String fallback) {

    /**
     * One value of a closed set, and what choosing it means.
     *
     * @param name the value on the wire
     * @param text what the value selects, in {@code messages/value.properties}
     */
    public record Value(String name, Supplier<String> text) {

        /** What the value selects, from its bundle. */
        public String description() {
            return text.get();
        }
    }

    /** What the argument is, from its bundle. */
    public String description() {
        return text.get();
    }

    /**
     * The JSON Schema type of nearly every argument: an address, a receipt and
     * a conflict token are opaque to the caller, and a typed shape would
     * invite it to construct one.
     */
    public static final String STRING = "string";

    /** A list of strings: the apparatus patterns of a draw, the options of a question. */
    public static final String ARRAY = "array";

    /** The caller's own keys: metadata. */
    public static final String OBJECT = "object";

    /** A yes or no: whether a question admits free text. */
    public static final String BOOLEAN = "boolean";

    /** A whole number: the page bound of a listing. */
    public static final String INTEGER = "integer";

    /**
     * The fallback of an optional argument that sets nothing when it is left
     * out: the call proceeds as if the argument did not exist. Published as a
     * {@code default} of JSON {@code null}; the description says what the
     * call does then.
     */
    public static final String UNSET = "";

    /** The one nested object a call may carry, by the name DEC-0040 gives it. */
    public static final String FIELDS = "fields";

    /** Where an argument sits in the call. */
    public enum Placement {

        /**
         * Names the target, or carries a transport artefact: scope, selector,
         * address, parent, duration, receipt, conflict token, idempotency key,
         * confirmation, and the filters and page bound of a listing.
         */
        TOP,

        /**
         * Under {@code fields}: everything the call writes into the task.
         *
         * <p>Its own object rather than a naming convention at the top level,
         * because a nested object is a place a schema can be closed.
         */
        FIELDS
    }

    public Argument {
        values = List.copyOf(values);
        if (required && fallback != null) {
            throw new IllegalArgumentException(name + " is required and declares a fallback");
        }
        if (!required && fallback == null) {
            throw new IllegalArgumentException(name + " is optional and declares no fallback: "
                + "DEC-0043 R17 has every optional argument name the value it takes when left "
                + "out or sent as null");
        }
    }

    /** A required top-level argument. */
    public static Argument top(String name, String type, Supplier<String> description) {
        return new Argument(name, type, true, Placement.TOP, description, null, List.of(),
            null);
    }

    /** An optional top-level argument and the value it takes when left out or null. */
    public static Argument top(String name, String type, String fallback,
                               Supplier<String> description) {
        return new Argument(name, type, false, Placement.TOP, description, null, List.of(),
            fallback);
    }

    /** A required argument under {@code fields}. */
    public static Argument field(String name, String type, Supplier<String> description) {
        return new Argument(name, type, true, Placement.FIELDS, description, null, List.of(),
            null);
    }

    /** An optional argument under {@code fields} and the value it takes when left out or null. */
    public static Argument field(String name, String type, String fallback,
                                 Supplier<String> description) {
        return new Argument(name, type, false, Placement.FIELDS, description, null, List.of(),
            fallback);
    }

    /** A required top-level list of strings, every element of which obeys one pattern. */
    public static Argument topList(String name, String itemPattern, Supplier<String> description) {
        return new Argument(name, ARRAY, true, Placement.TOP, description, itemPattern,
            List.of(), null);
    }

    /** A string that takes one of a closed set of values; fallback null where it is required. */
    public static Argument oneOf(String name, Placement placement, String fallback,
                                 List<Value> values, Supplier<String> description) {
        return new Argument(name, STRING, fallback == null, placement, description, null,
            values, fallback);
    }

    /** One value of a closed set. */
    public static Value value(String name, Supplier<String> description) {
        return new Value(name, description);
    }

    /** The names of the closed set of values, in their declared order; empty where there is none. */
    public List<String> valueNames() {
        return values.stream().map(Value::name).toList();
    }

    public boolean isField() {
        return placement == Placement.FIELDS;
    }
}
