package ai.kumbuka.dispatch.api;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The verbs the REST surface expresses in colon notation, and the depth each
 * one acts at.
 *
 * <h2>Why colon notation is forced</h2>
 *
 * The id part of an address may be multi-segment, so a verb written as a
 * trailing path segment could not be told apart from a further id segment.
 * The colon appears in no address part, which is what makes it decidable. This
 * is a consequence of the address space, not a style choice, and it is why the
 * table below exists at all.
 *
 * <h2>Why it is a table rather than eleven annotations</h2>
 *
 * Measured against Quarkus REST 3.33.2 on 2026-09-01: a path template takes
 * the whole segment it appears in. {@code @Path("{id}:send")} registers as
 * {@code {id}} and matches {@code 164.0:send} with the verb inside the
 * variable, and a narrowing regex on the template is ignored. So a route per
 * verb is not available from the framework, and the eleven would silently
 * collapse into one — which is the failure mode where every transition answers
 * whichever handler happened to sort first.
 *
 * <p>What is registered instead is one binding per address depth, and the verb
 * is split off here. The <strong>outward form is unchanged</strong> — a caller
 * still writes {@code POST …/164.0:send} — and it is the outward form the
 * conformance probe measures, end to end, against the specification. What
 * changed is where the split happens, not what a client sees.
 */
public enum CustomMethod {

    // ---- Carried, at item depth -----------------------------------------
    SEND("send", Depth.ITEM),
    ACCEPT("accept", Depth.ITEM),
    CLAIM("claim", Depth.ITEM),
    RELEASE("release", Depth.ITEM),
    ABANDON("abandon", Depth.ITEM),
    BLOCK("block", Depth.ITEM),
    RESUME("resume", Depth.ITEM),
    CLOSE("close", Depth.ITEM),
    CONSUME("consume", Depth.ITEM),

    /**
     * The one transition at collection depth.
     *
     * <p>Admissible there because its verb contract declares set semantics,
     * and the only declarable set semantics is exactly one. Undeclared stays
     * fail-closed, which is why every other verb above sits at item depth and
     * answers 405 when addressed at a collection.
     */
    CLAIM_NEXT("claim_next", Depth.COLLECTION),

    // ---- Not carried, and answered by name rather than left absent ------
    WITHDRAW("withdraw", Depth.ITEM),
    VALIDATE("validate", Depth.ITEM);

    /** The address depth a verb acts at. Undeclared would mean item only. */
    public enum Depth {
        /** A complete address. */
        ITEM,
        /** A truncated address, which only a declared set semantics admits. */
        COLLECTION
    }

    /** The separator. It appears in no address part, which is the whole point. */
    public static final char SEPARATOR = ':';

    private final String verb;
    private final Depth depth;

    CustomMethod(String verb, Depth depth) {
        this.verb = verb;
        this.depth = depth;
    }

    public String verb() {
        return verb;
    }

    public Depth depth() {
        return depth;
    }

    /** The verbs of one depth, for the conformance probe and for routing. */
    public static List<CustomMethod> at(Depth depth) {
        return Arrays.stream(values()).filter(m -> m.depth == depth).toList();
    }

    /**
     * Splits a path segment into the address part and the verb, where there is
     * one.
     *
     * <p>Split at the <em>last</em> colon rather than the first, because the
     * address part is the thing that may grow segments and the verb is the
     * thing that may not. Splitting at the first colon would make a future
     * multi-segment id shadow the verb.
     *
     * @return the split, or empty when the segment carries no colon at all —
     *         which is a plain address and not a malformed verb
     */
    public static Optional<Split> split(String segment, Depth depth) {
        int at = segment.lastIndexOf(SEPARATOR);
        if (at < 0) {
            return Optional.empty();
        }

        String address = segment.substring(0, at);
        String verb = segment.substring(at + 1);

        return Optional.of(new Split(address, verb,
            at(depth).stream().filter(m -> m.verb.equals(verb)).findFirst().orElse(null)));
    }

    /**
     * A segment taken apart: what addresses, what acts, and which verb it is
     * if this depth carries one by that name.
     *
     * @param method null when no verb of this depth is spelled that way, which
     *               is a different thing from the segment carrying no verb
     */
    public record Split(String address, String verb, CustomMethod method) {

        public boolean isKnown() {
            return method != null;
        }
    }
}
