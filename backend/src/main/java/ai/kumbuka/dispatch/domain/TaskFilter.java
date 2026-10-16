/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a listing of tasks may be narrowed by, and nothing else.
 *
 * <p>The grammar of a listing's filter: the fields are written out, values
 * within a field are comma-separated alternatives, separate fields are
 * conjunctive, an undeclared field is refused by name rather than ignored, and
 * a value a field cannot take is refused rather than matched against nothing.
 * Four fields (concept section 5): the effective state, apparatus patterns,
 * the bracket number, and a list of addresses.
 *
 * <p>The state is the EFFECTIVE one. A task whose lease lapsed is listed as
 * {@code open}, whatever its row says, for the reason the filter of the old
 * kernel refuses to filter on the stored holder.
 */
public record TaskFilter(
    Set<TaskState> states,
    List<String> apparatusPatterns,
    Set<Integer> brackets,
    Set<ExchangeAddress> addresses) {

    /** The declared fields, by their names on the wire. */
    public enum Field {
        STATE("state"),
        APPARATUS("apparatus"),
        BRACKET("bracket"),
        ADDRESS("address");

        private final String wireName;

        Field(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        static Optional<Field> byWireName(String name) {
            for (Field f : values()) {
                if (f.wireName.equals(name)) {
                    return Optional.of(f);
                }
            }
            return Optional.empty();
        }

        /** Every declared name, for a refusal that says what would have worked. */
        public static List<String> wireNames() {
            return java.util.Arrays.stream(values()).map(Field::wireName).toList();
        }
    }

    /** {@code <selector>/<number>.<sub>}, the short form of a task's address. */
    private static final Pattern ADDRESS = Pattern.compile("([a-z][a-z0-9-]{0,62})/(\\d+)\\.(\\d+)");

    public TaskFilter {
        states = Set.copyOf(states);
        apparatusPatterns = List.copyOf(apparatusPatterns);
        brackets = Set.copyOf(brackets);
        addresses = Set.copyOf(addresses);
    }

    /** No narrowing: every task of the selector. */
    public static TaskFilter none() {
        return new TaskFilter(Set.of(), List.of(), Set.of(), Set.of());
    }

    /**
     * Reads a filter from raw field/value pairs, refusing anything undeclared.
     *
     * @param raw field name to its comma-separated values, as the caller wrote them
     */
    public static TaskFilter of(Map<String, String> raw) {
        boolean undeclared = raw.keySet().stream().anyMatch(name -> Field.byWireName(name).isEmpty());
        if (undeclared) {
            // The names that arrived are the caller's and stay with it (DEC-0043 R19).
            throw new DispatchException(DispatchException.Reason.FILTER_FIELD_UNKNOWN,
                "a listing of tasks filters on " + String.join(", ", Field.wireNames())
                    + " and on nothing else; an undeclared field is refused rather than "
                    + "ignored.", List.of());
        }
        return new TaskFilter(
            states(values(raw, Field.STATE)),
            values(raw, Field.APPARATUS),
            brackets(values(raw, Field.BRACKET)),
            addresses(values(raw, Field.ADDRESS)));
    }

    /**
     * The filter in one canonical spelling: every field, its values sorted, so
     * two filters that admit the same tasks spell alike whatever order the
     * caller wrote the alternatives in. What a cursor's fingerprint is taken of.
     */
    String canonical() {
        return "state=" + String.join(",", states.stream().map(TaskState::wireName).sorted().toList())
            + ";apparatus=" + String.join(",", apparatusPatterns.stream().sorted().distinct().toList())
            + ";bracket=" + String.join(",", brackets.stream().sorted().map(String::valueOf).toList())
            + ";address=" + String.join(",", addresses.stream().map(ExchangeAddress::toString)
                .sorted().toList());
    }

    private static List<String> values(Map<String, String> raw, Field field) {
        String value = raw.get(field.wireName());
        if (value == null) {
            return List.of();
        }
        List<String> parts = java.util.Arrays.stream(value.split(",", -1)).map(String::trim)
            .toList();
        int empty = java.util.stream.IntStream.range(0, parts.size())
            .filter(i -> parts.get(i).isEmpty()).findFirst().orElse(-1);
        if (empty >= 0) {
            throw refused(field, "an empty value", parts.size(), empty);
        }
        return parts;
    }

    private static Set<TaskState> states(List<String> values) {
        Set<TaskState> out = new LinkedHashSet<>();
        for (int i = 0; i < values.size(); i++) {
            try {
                out.add(TaskState.fromWireName(values.get(i)));
            } catch (IllegalArgumentException e) {
                throw refused(Field.STATE, "a value that is no state", values.size(), i);
            }
        }
        return out;
    }

    private static Set<Integer> brackets(List<String> values) {
        Set<Integer> out = new LinkedHashSet<>();
        for (int i = 0; i < values.size(); i++) {
            int at = i;
            out.add(bracketNumber(values.get(i)).orElseThrow(() ->
                refused(Field.BRACKET, "a value that is no bracket number", values.size(), at)));
        }
        return out;
    }

    /** A bracket number: a whole number from 1; empty where the text names none. */
    private static Optional<Integer> bracketNumber(String value) {
        try {
            int number = Integer.parseInt(value);
            return number < 1 ? Optional.empty() : Optional.of(number);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Set<ExchangeAddress> addresses(List<String> values) {
        Set<ExchangeAddress> out = new LinkedHashSet<>();
        for (int i = 0; i < values.size(); i++) {
            Matcher m = ADDRESS.matcher(values.get(i));
            if (!m.matches()) {
                throw refused(Field.ADDRESS, "a value that is no task address of the form "
                    + "selector/number.sub", values.size(), i);
            }
            out.add(new ExchangeAddress(m.group(1), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))));
        }
        return out;
    }

    /**
     * A value the field cannot take, named by the filter and, where the filter
     * carries several values, by the position of the first it cannot take —
     * never by the value (DEC-0042, DEC-0043 R18, R19).
     */
    private static DispatchException refused(Field field, String what, int count, int at) {
        String path = count > 1 ? field.wireName() + "[" + at + "]" : field.wireName();
        return new DispatchException(DispatchException.Reason.FILTER_VALUE_REFUSED,
            "the filter " + path + " carries " + what + ". A value the field cannot take is "
                + "refused rather than matched against nothing.",
            List.of(path));
    }
}
