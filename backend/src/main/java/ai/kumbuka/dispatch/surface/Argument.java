package ai.kumbuka.dispatch.surface;

/**
 * One argument of one call, and where it travels.
 *
 * <p>The placement is the part that is easy to read as decoration and is not.
 * DEC-0040 puts what the call <em>writes</em> under {@code fields} and what
 * chooses the target or carries the transport under {@code fields}' parent.
 * The split is what lets a caller see, from the shape of its own call, which
 * half of it lands in the exchange — and it is what lets the schema be closed
 * at both levels instead of only the outer one, which is how {@code draft}
 * reached the service and was dropped.
 *
 * @param name        the argument's name on the wire
 * @param type        its JSON Schema type
 * @param required    whether the call refuses without it
 * @param placement   top level, or nested under {@code fields}
 * @param description what it is, in one sentence; also the {@code {what}} of
 *                    an {@code ARGUMENT_MISSING} refusal, which is why it
 *                    reads as a noun phrase rather than an instruction
 * @param itemPattern for a {@link #ARRAY} argument, the regular expression
 *                    every element obeys; null for every other argument. It
 *                    travels into the published schema so a conforming client
 *                    can refuse a malformed element before sending it — the
 *                    schema is a description and the surface is the
 *                    enforcement, and this is the description's half
 */
public record Argument(String name, String type, boolean required, Placement placement,
                       String description, String itemPattern) {

    /**
     * The JSON Schema type every argument of this surface has.
     *
     * <p>All of them, and that is not an accident to be tidied away later: an
     * address, a receipt and a conflict token are opaque to the caller, and a
     * typed shape would invite it to construct one. {@code metadata} is the
     * single exception and declares its own type.
     */
    public static final String STRING = "string";

    /**
     * The one argument of this surface that is not a scalar: a list of
     * patterns.
     *
     * <p>The exception is narrow and earned. {@code dispatch_take_next} draws
     * from a set and has to say which part of that set — several alternatives
     * at once, because a controller that serves two apparatus values would
     * otherwise have to poll twice and would draw them in the wrong order. A
     * comma-separated string would carry the same cardinality while hiding it
     * from the schema, which is how a caller comes to send a list the surface
     * reads as one long name.
     */
    public static final String ARRAY = "array";

    /** Where an argument sits in the call. */
    public enum Placement {

        /**
         * Chooses the target, or carries a transport artefact: {@code scope},
         * {@code selector}, {@code address}, {@code parent}, {@code duration},
         * {@code receipt}, {@code conflict_token}, {@code idempotency_key}.
         */
        TOP,

        /**
         * Under {@code fields}: everything the call writes into the exchange.
         *
         * <p>Its own object rather than a naming convention at the top level,
         * because a nested object is a place a schema can be closed. A caller
         * that misspells a written value gets the same refusal whether it
         * misspelled it at the top or one level down.
         */
        FIELDS
    }

    public static Argument top(String name, String type, boolean required, String description) {
        return new Argument(name, type, required, Placement.TOP, description, null);
    }

    public static Argument field(String name, String type, boolean required,
                                 String description) {
        return new Argument(name, type, required, Placement.FIELDS, description, null);
    }

    /**
     * A top-level list of strings, every element of which obeys one pattern.
     *
     * <p>Always required, because the only such argument there is exists to
     * stop a call being made without it. An optional list of patterns would be
     * an argument whose absence means "match everything", which is precisely
     * the default the pattern rule refuses.
     */
    public static Argument topList(String name, String itemPattern, String description) {
        return new Argument(name, ARRAY, true, Placement.TOP, description, itemPattern);
    }

    public boolean isField() {
        return placement == Placement.FIELDS;
    }
}
