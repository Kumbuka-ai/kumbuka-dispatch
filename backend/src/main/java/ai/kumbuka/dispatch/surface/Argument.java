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
 */
public record Argument(String name, String type, boolean required, Placement placement,
                       String description) {

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
        return new Argument(name, type, required, Placement.TOP, description);
    }

    public static Argument field(String name, String type, boolean required,
                                 String description) {
        return new Argument(name, type, required, Placement.FIELDS, description);
    }

    public boolean isField() {
        return placement == Placement.FIELDS;
    }
}
