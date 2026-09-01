package ai.kumbuka.dispatch.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a caller may narrow a listing by, and nothing else.
 *
 * <h2>Why this is an enumeration and not an expression language</h2>
 *
 * The REST surface is the front door of an AGPL product, so it is a published
 * contract from the first day it answers. An expression language is a surface
 * that cannot be closed again: every operator it admits becomes something a
 * caller depends on, and the set of queries it can express is not knowable in
 * advance — which means neither is the set of indexes needed to serve them,
 * nor the cost of the worst one.
 *
 * <p>So the filterable fields are written out below, one enum constant each.
 * Values within a field are comma-separated and read disjunctively; separate
 * fields are conjunctive. That is the whole grammar. It admits "the open and
 * needs-input exchanges addressed to code" and refuses everything else,
 * including anything that would need an operator to express.
 *
 * <h2>Fail-closed, and the shape of the refusal</h2>
 *
 * A field that is not declared here is not filterable, and asking for one is a
 * TYPED REFUSAL naming the field. It is deliberately not ignored: an ignored
 * filter returns a plausible answer — the full set, looking exactly like a
 * correct narrow one — and a caller has no way to tell. That failure is worse
 * than a refusal precisely because it looks like success.
 *
 * <p>The same holds for a value the field cannot take. {@code status=banana}
 * silently matching nothing would be an empty page that reads as "there is
 * nothing here", which is a different statement from "you asked for something
 * that does not exist".
 *
 * <h2>What is deliberately not here</h2>
 *
 * <ul>
 *   <li><strong>title and body.</strong> Free-text matching is an expression
 *       language with one operator, and the body is the field the projection
 *       bolt exists to withhold — a filter over it would leak by hit-count
 *       what the projection refuses to hand over.</li>
 *   <li><strong>the holder.</strong> The effective holder is computed against
 *       the clock and is not a stored value; filtering on the stored column
 *       would return exchanges whose claim has lapsed as though they were
 *       taken.</li>
 *   <li><strong>dates and ranges.</strong> A range needs comparison operators,
 *       and those are the first two lines of the expression language this
 *       refuses to be.</li>
 * </ul>
 */
public record QueryFilter(
    Set<ExchangeStatus> statuses,
    Set<String> apparatuses,
    Set<Integer> numbers) {

    /** The declared fields, and their names on the wire. */
    public enum Field {
        STATUS("status"),
        APPARATUS("apparatus"),
        NUMBER("number");

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

        /** Every declared name, for a refusal that says what WOULD have worked. */
        public static List<String> wireNames() {
            return java.util.Arrays.stream(values()).map(Field::wireName).toList();
        }
    }

    /** No narrowing at all: every exchange of the selector. */
    public static QueryFilter none() {
        return new QueryFilter(Set.of(), Set.of(), Set.of());
    }

    /**
     * Reads a filter from raw field/value pairs, refusing anything undeclared.
     *
     * <p>Every field is checked before any is applied, so a call naming two
     * unknown fields is told about both rather than about whichever came
     * first. A caller fixing one at a time is a caller making two round trips
     * to learn what one refusal could have said.
     *
     * @param raw field name to its comma-separated values, exactly as the
     *            caller wrote them
     */
    public static QueryFilter of(Map<String, String> raw) {
        List<String> unknownFields = new ArrayList<>();
        for (String name : raw.keySet()) {
            if (Field.byWireName(name) == null) {
                unknownFields.add(name);
            }
        }
        if (!unknownFields.isEmpty()) {
            throw new DispatchException(DispatchException.Reason.FILTER_FIELD_UNKNOWN,
                "cannot filter on " + String.join(", ", unknownFields) + ". This listing "
                    + "filters on " + String.join(", ", Field.wireNames()) + " and on "
                    + "nothing else — an undeclared field is refused rather than ignored, "
                    + "because an ignored filter answers with the full set and looks "
                    + "exactly like a correct narrow one.",
                unknownFields);
        }

        return new QueryFilter(
            statuses(values(raw, Field.STATUS)),
            new LinkedHashSet<>(values(raw, Field.APPARATUS)),
            numbers(values(raw, Field.NUMBER)));
    }

    /**
     * The comma-separated values of one field, or an empty list when the
     * caller did not name it.
     *
     * <p>A field named with nothing after it is refused rather than treated as
     * absent: {@code ?status=} is a caller that meant something, and guessing
     * which of "no filter" and "a filter I failed to write" they meant is
     * exactly the guess this whole type exists to avoid.
     */
    private static List<String> values(Map<String, String> raw, Field field) {
        String value = raw.get(field.wireName());
        if (value == null) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split(",", -1)) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                throw new DispatchException(DispatchException.Reason.FILTER_VALUE_REFUSED,
                    "the filter on '" + field.wireName() + "' carries an empty value. An "
                        + "empty value is refused rather than read as 'no filter': the two "
                        + "mean opposite things and only the caller knows which was meant.",
                    List.of(field.wireName()));
            }
            parts.add(trimmed);
        }
        return parts;
    }

    private static Set<ExchangeStatus> statuses(List<String> values) {
        Set<ExchangeStatus> out = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String value : values) {
            try {
                out.add(ExchangeStatus.fromWireName(value));
            } catch (IllegalArgumentException e) {
                unknown.add(value);
            }
        }
        if (!unknown.isEmpty()) {
            throw new DispatchException(DispatchException.Reason.FILTER_VALUE_REFUSED,
                "no such status: " + String.join(", ", unknown) + ". A status this scheme "
                    + "does not have is refused rather than matched against nothing — an "
                    + "empty page reads as 'there is nothing here', which is not what "
                    + "happened.",
                unknown);
        }
        return out;
    }

    private static Set<Integer> numbers(List<String> values) {
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
            throw new DispatchException(DispatchException.Reason.FILTER_VALUE_REFUSED,
                "not a bracket number: " + String.join(", ", unusable) + ". Numbers start "
                    + "at 1 and are whole, so anything else could never match and is "
                    + "refused rather than answered with an empty page.",
                unusable);
        }
        return out;
    }

    /** True when the caller narrowed nothing, which is a listing of the selector. */
    public boolean isEmpty() {
        return statuses.isEmpty() && apparatuses.isEmpty() && numbers.isEmpty();
    }
}
