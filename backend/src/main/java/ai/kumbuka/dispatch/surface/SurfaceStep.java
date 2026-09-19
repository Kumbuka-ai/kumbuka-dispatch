package ai.kumbuka.dispatch.surface;

/**
 * A step a refusal's message points at, and the call each surface makes for it.
 *
 * <p>Section 4.2 of the contract: "A pattern never names a call literally; it
 * names a step, written below as {@code <take>}, {@code <deliver>}, {@code
 * <query>}, {@code <read>}, and the surface fills in its own call for that
 * step." So the catalogue's patterns carry {@code {take}} and its kin, and the
 * value is supplied where the refusal is worded, from the surface the caller
 * called through.
 *
 * <p>The failure this exists to stop was measured in review on 2026-09-19: a REST
 * caller refused with {@code NO_ANSWER_DELIVERED} was told "the holder delivers
 * with dispatch_deliver_return" — a call that does not exist on its surface.
 * Hardcoding the assistant surface's name into the pattern is exactly the shape
 * that cannot be caught by reading the pattern, because the pattern reads
 * correctly for the surface it was written on.
 *
 * <p>The generic surface's fillings are phrases rather than single verbs where
 * the act takes two verbs there. {@code dispatch_deliver_return} is one call on
 * the assistant surface and is {@code update} followed by {@code block} on the
 * generic one; naming only one of the two would send a REST caller half way.
 */
public enum SurfaceStep {

    /** Taking an open exchange up. */
    TAKE("take", "dispatch_take", "takeup"),

    /** The holder delivering its answer. */
    DELIVER("deliver", "dispatch_deliver_return", "update and then block"),

    /** Listing a bracket kind. */
    QUERY("query", "dispatch_query", "query"),

    /** Reading one exchange. */
    READ("read", "dispatch_read", "read");

    private final String placeholder;
    private final String assistant;
    private final String generic;

    SurfaceStep(String placeholder, String assistant, String generic) {
        this.placeholder = placeholder;
        this.assistant = assistant;
        this.generic = generic;
    }

    /** The name this step goes by inside a pattern, without its braces. */
    public String placeholder() {
        return placeholder;
    }

    /** The call this step is made with, on one surface. */
    public String on(Surface surface) {
        return surface == Surface.MCP ? assistant : generic;
    }
}
