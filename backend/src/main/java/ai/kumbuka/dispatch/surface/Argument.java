package ai.kumbuka.dispatch.surface;

import java.util.List;

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
 * @param description what it is; also the {@code {what}} of an {@code
 *                    ARGUMENT_MISSING} refusal, which is why it reads as a noun
 *                    phrase rather than an instruction
 * @param itemPattern for an {@link #ARRAY} argument, the regular expression
 *                    every element obeys, or null. It travels into the
 *                    published schema so a conforming client can refuse a
 *                    malformed element before sending it; the surface enforces
 *                    the same rule
 * @param values      the closed set of values a {@link #STRING} argument takes,
 *                    or empty where it takes any string. Published as the
 *                    schema's {@code enum} and enforced by the surface
 */
public record Argument(String name, String type, boolean required, Placement placement,
                       String description, String itemPattern, List<String> values) {

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
    }

    public static Argument top(String name, String type, boolean required, String description) {
        return new Argument(name, type, required, Placement.TOP, description, null, List.of());
    }

    public static Argument field(String name, String type, boolean required,
                                 String description) {
        return new Argument(name, type, required, Placement.FIELDS, description, null,
            List.of());
    }

    /** A top-level list of strings, every element of which obeys one pattern. */
    public static Argument topList(String name, boolean required, String itemPattern,
                                   String description) {
        return new Argument(name, ARRAY, required, Placement.TOP, description, itemPattern,
            List.of());
    }

    /** A string that takes one of a closed set of values. */
    public static Argument oneOf(String name, Placement placement, boolean required,
                                 List<String> values, String description) {
        return new Argument(name, STRING, required, placement, description, null, values);
    }

    public boolean isField() {
        return placement == Placement.FIELDS;
    }
}
