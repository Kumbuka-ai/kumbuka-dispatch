package ai.kumbuka.dispatch.adapter.rest;

import java.util.Arrays;
import java.util.Optional;

/**
 * The routes of the REST surface: one per call, written by hand.
 *
 * <p>The calls are the twenty-five of the declaration, under the same names
 * without the {@code dispatch_} prefix the assistant surface carries. The
 * routes are not generated from the declaration; a test holds the two
 * statements together: every declared call has exactly one route here, and no
 * route stands here without a declared call. A route is therefore keyed by the
 * call's NAME rather than by the declaration's constant — a route that named a
 * constant could not exist without a call, and the second half of that test
 * could never be observed failing.
 *
 * <h2>The path form</h2>
 *
 * The form of the existing adapter: {@code /api/{scope}/{selector}} for a
 * collection, {@code /api/{scope}/{selector}/{number}.{sub}} for one task, and
 * a call that is not the plain read, write or delete of what the path names
 * as a custom method in colon notation — {@code POST …/164.0:send}. The colon
 * is forced: the id part may grow segments and a trailing verb segment could
 * not be told from one. A path template takes the whole segment it appears in
 * (measured against Quarkus REST 3.33.2, 2026-09-01), so the colon is split
 * off by {@link #split} rather than routed by the framework.
 */
public enum RestRoute {

    CREATE("create", "POST", Depth.COLLECTION, false),
    QUERY("query", "GET", Depth.COLLECTION, false),
    CLAIM_NEXT("claim_next", "POST", Depth.COLLECTION, true),

    READ("read", "GET", Depth.ITEM, false),
    READ_TEXT("read_text", "GET", Depth.ITEM, true),
    UPDATE("update", "PATCH", Depth.ITEM, false),
    DELETE("delete", "DELETE", Depth.ITEM, false),

    SEND("send", "POST", Depth.ITEM, true),
    CLAIM("claim", "POST", Depth.ITEM, true),
    RELEASE("release", "POST", Depth.ITEM, true),
    DEFER("defer", "POST", Depth.ITEM, true),
    RENEW("renew", "POST", Depth.ITEM, true),
    ASK("ask", "POST", Depth.ITEM, true),
    ANSWER("answer", "POST", Depth.ITEM, true),
    HOLD("hold", "POST", Depth.ITEM, true),
    RESUME("resume", "POST", Depth.ITEM, true),
    DELIVER("deliver", "POST", Depth.ITEM, true),
    REWORK("rework", "POST", Depth.ITEM, true),
    ACCEPT("accept", "POST", Depth.ITEM, true),
    REJECT("reject", "POST", Depth.ITEM, true),
    FAIL("fail", "POST", Depth.ITEM, true),
    WITHDRAW("withdraw", "POST", Depth.ITEM, true),
    ANNOTATE("annotate", "POST", Depth.ITEM, true),
    RELATE("relate", "POST", Depth.ITEM, true),
    UNRELATE("unrelate", "POST", Depth.ITEM, true);

    /** Where a route acts. */
    public enum Depth {
        /** {@code /api/{scope}/{selector}}: a bracket kind in a scope. */
        COLLECTION,
        /** {@code /api/{scope}/{selector}/{id}}: one task. */
        ITEM
    }

    /** The separator of a custom method. It appears in no address part. */
    public static final char SEPARATOR = ':';

    private final String call;
    private final String method;
    private final Depth depth;
    private final boolean custom;

    RestRoute(String call, String method, Depth depth, boolean custom) {
        this.call = call;
        this.method = method;
        this.depth = depth;
        this.custom = custom;
    }

    /** The call this route makes, by its name on this surface. */
    public String call() {
        return call;
    }

    public String method() {
        return method;
    }

    public Depth depth() {
        return depth;
    }

    /** Whether the call is named in colon notation after the address. */
    public boolean custom() {
        return custom;
    }

    /** The outward form, as a caller writes it. */
    public String form() {
        String base = depth == Depth.COLLECTION
            ? "/api/{scope}/{selector}"
            : "/api/{scope}/{selector}/{id}";
        return method + " " + base + (custom ? SEPARATOR + call : "");
    }

    /**
     * The route a request takes, if there is one.
     *
     * @param verb the colon segment's verb, or null where the segment has none
     */
    public static Optional<RestRoute> find(String method, Depth depth, String verb) {
        return Arrays.stream(values())
            .filter(r -> r.method.equals(method) && r.depth == depth
                && (verb == null ? !r.custom : r.custom && r.call.equals(verb)))
            .findFirst();
    }

    /** The route of one call, by its name, if it has one. */
    public static Optional<RestRoute> of(String call) {
        return Arrays.stream(values()).filter(r -> r.call.equals(call)).findFirst();
    }

    /** Every route at one depth and the methods it is reached with, for an {@code Allow}. */
    public static String allow(Depth depth) {
        return String.join(", ", Arrays.stream(values())
            .filter(r -> r.depth == depth)
            .map(RestRoute::method)
            .distinct()
            .toList());
    }

    /**
     * A path segment taken apart at its last colon: the address part and the
     * verb, or the segment and null where it carries no colon.
     */
    public static String[] split(String segment) {
        int at = segment.lastIndexOf(SEPARATOR);
        return at < 0
            ? new String[] {segment, null}
            : new String[] {segment.substring(0, at), segment.substring(at + 1)};
    }
}
