package ai.kumbuka.dispatch.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

        static Field byWireName(String name) {
            for (Field f : values()) {
                if (f.wireName.equals(name)) {
                    return f;
                }
            }
            return null;
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
        List<String> unknown = raw.keySet().stream()
            .filter(name -> Field.byWireName(name) == null)
            .toList();
        if (!unknown.isEmpty()) {
            throw new DispatchException(DispatchException.Reason.FILTER_FIELD_UNKNOWN,
                "cannot filter on " + String.join(", ", unknown) + ". A listing of tasks "
                    + "filters on " + String.join(", ", Field.wireNames()) + " and on "
                    + "nothing else; an undeclared field is refused rather than ignored.",
                unknown);
        }
        return new TaskFilter(
            states(values(raw, Field.STATE)),
            values(raw, Field.APPARATUS),
            brackets(values(raw, Field.BRACKET)),
            addresses(values(raw, Field.ADDRESS)));
    }

    /** Whether a task in effective state {@code state} at {@code address} passes. */
    boolean admits(TaskState state, ExchangeAddress address) {
        return (states.isEmpty() || states.contains(state))
            && (addresses.isEmpty() || addresses.contains(address));
    }

    private static List<String> values(Map<String, String> raw, Field field) {
        String value = raw.get(field.wireName());
        if (value == null) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split(",", -1)) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                throw refused(field, "an empty value", List.of(field.wireName()));
            }
            parts.add(trimmed);
        }
        return parts;
    }

    private static Set<TaskState> states(List<String> values) {
        Set<TaskState> out = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String value : values) {
            try {
                out.add(TaskState.fromWireName(value));
            } catch (IllegalArgumentException e) {
                unknown.add(value);
            }
        }
        if (!unknown.isEmpty()) {
            throw refused(Field.STATE, "no such state: " + String.join(", ", unknown), unknown);
        }
        return out;
    }

    private static Set<Integer> brackets(List<String> values) {
        Set<Integer> out = new LinkedHashSet<>();
        List<String> unusable = new ArrayList<>();
        for (String value : values) {
            try {
                int number = Integer.parseInt(value);
                if (number < 1) {
                    unusable.add(value);
                } else {
                    out.add(number);
                }
            } catch (NumberFormatException e) {
                unusable.add(value);
            }
        }
        if (!unusable.isEmpty()) {
            throw refused(Field.BRACKET, "not a bracket number: " + String.join(", ", unusable),
                unusable);
        }
        return out;
    }

    private static Set<ExchangeAddress> addresses(List<String> values) {
        Set<ExchangeAddress> out = new LinkedHashSet<>();
        List<String> unusable = new ArrayList<>();
        for (String value : values) {
            Matcher m = ADDRESS.matcher(value);
            if (m.matches()) {
                out.add(new ExchangeAddress(m.group(1), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))));
            } else {
                unusable.add(value);
            }
        }
        if (!unusable.isEmpty()) {
            throw refused(Field.ADDRESS, "not a task address of the form selector/number.sub: "
                + String.join(", ", unusable), unusable);
        }
        return out;
    }

    private static DispatchException refused(Field field, String what, List<String> offenders) {
        return new DispatchException(DispatchException.Reason.FILTER_VALUE_REFUSED,
            "the filter on '" + field.wireName() + "' carries " + what + ". A value the field "
                + "cannot take is refused rather than matched against nothing.",
            offenders);
    }
}
