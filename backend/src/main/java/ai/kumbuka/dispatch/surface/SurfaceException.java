package ai.kumbuka.dispatch.surface;

/**
 * A typed refusal the surface itself produces, as distinct from one the domain
 * produces.
 *
 * <p>The two are kept apart deliberately. {@code DispatchException} says
 * something about an exchange — its state, its claim, its freeze — and the
 * domain is the only place that knows those things. This one says something
 * about the <em>call</em>: the address does not parse, the scheme does not
 * carry the verb, a writing verb arrived on a collection. None of that needs
 * an exchange to exist, and most of it is decided before one is looked for.
 *
 * <p>Every reason below names a status class and keeps it. A caller that has
 * to tell "you addressed this wrongly" from "that verb is not part of this
 * scheme" from "there is nothing there" cannot do it from prose, and a surface
 * that answered all three the same way would be indistinguishable from one
 * with unbuilt routes.
 */
public class SurfaceException extends RuntimeException {

    /**
     * The category of a surface refusal, and the HTTP status it carries.
     *
     * <p>The status travels with the reason rather than being decided at the
     * mapper, because the choice is part of the published contract and a
     * mapper is exactly where a choice gets quietly changed.
     */
    public enum Reason {

        /**
         * The address violates the production. Stage 1, and decidable without
         * knowing any scope — which is why answering 400 leaks nothing and why
         * this check sits in front of everything else.
         */
        ADDRESS_MALFORMED(400),

        /**
         * The dispatch scheme does not carry this verb.
         *
         * <p>422 and never 404: a not-found says the object is missing, an
         * unimplemented path says nothing at all, and both invite the caller
         * to retry. A category error says the call will never work, and names
         * why.
         */
        VERB_NOT_CARRIED(422),

        /**
         * The verb carries no declared address depth, so fail-closed leaves it
         * unbuildable.
         *
         * <p>Its own reason rather than a reuse of the one above: the verb is
         * not refused on the merits, it is refused because the specification
         * never said at which depth it acts, and the default is closed.
         */
        VERB_DEPTH_UNDECLARED(422),

        /**
         * Withdrawal is a ratchet and is not offered on the machine surface.
         *
         * <p>Named separately because the refusal has an address: the console.
         * "Not carried" would send the caller looking for another verb; this
         * one tells it where the act lives.
         */
        WITHDRAWAL_VIA_CONSOLE_ONLY(422),

        /**
         * A writing verb arrived on a truncated address that declares no set
         * semantics.
         *
         * <p>405, and the answer carries {@code Allow}. HTTP does not forbid a
         * write on a collection — it merely finds it unusual — so this is an
         * explicit check rather than something the framework does for us.
         */
        WRITE_ON_TRUNCATED_ADDRESS(405),

        /**
         * The verb is real, the address is real, and the verb does not apply
         * at it.
         *
         * <p>{@code dispatch_close_bracket} at a child, a sub-collection that
         * exists only at a bracket root. Distinct from
         * {@link #ADDRESS_MALFORMED}, which this borrowed before: the address
         * obeys the production and names something the caller can see, so a
         * form refusal sends it correcting a spelling that was right. The
         * contract declares {@code CALL_NOT_AT_THIS_ADDRESS} for exactly this.
         *
         * <p>409 rather than 405: the pairing is wrong, not the HTTP method,
         * and a 405 would have to carry an {@code Allow} listing methods that
         * are not the problem.
         */
        CALL_NOT_AT_THIS_ADDRESS(409),

        /** A field write arrived without the conflict token it declares. */
        CONFLICT_TOKEN_MISSING(428),

        /** The conflict token presented is not the one the object holds. */
        CONFLICT_TOKEN_STALE(412),

        /** The request body is absent or does not carry what the verb needs. */
        PAYLOAD_MALFORMED(400),

        /**
         * A claim's duration is absent or is not a positive ISO-8601 duration.
         *
         * <p>Its own reason rather than a payload fault, because the surface
         * contract declares a pattern for exactly this and could not reach it
         * otherwise: the caller was told its payload was malformed, which is
         * true and says nothing about which value it should correct.
         */
        CLAIM_DURATION_MALFORMED(400),

        /**
         * A draw arrived naming no apparatus at all.
         *
         * <p>Its own reason rather than a payload fault, for the same argument
         * {@link #CLAIM_DURATION_MALFORMED} makes: the caller needs to know
         * which value to supply, and "the payload is malformed" names none.
         *
         * <p>There is no default, and the absence of one is the whole point of
         * the argument. A draw with no pattern takes the next exchange of any
         * apparatus, which is how a controller polling for its own work claims
         * a commission addressed to somebody else.
         */
        APPARATUS_PATTERN_MISSING(400),

        /**
         * An apparatus pattern carries a character the pattern language does
         * not have.
         *
         * <p>Separate from {@link #APPARATUS_PATTERN_UNBOUNDED}: this one is a
         * spelling to correct, that one is a pattern that is well spelled and
         * says too much. A caller told "malformed" about {@code *} would go
         * looking for a typo it does not have.
         */
        APPARATUS_PATTERN_MALFORMED(400),

        /**
         * An apparatus pattern matches every apparatus there is.
         *
         * <p>Refused although it is well formed, because it is the blind draw
         * the argument exists to close: a pattern of nothing but wildcards
         * narrows nothing, and a caller that sent one would be polling for
         * everybody's work while believing it had declared whose it wanted.
         */
        APPARATUS_PATTERN_UNBOUNDED(400),

        /**
         * The request body carries a field this call does not read.
         *
         * <p>Named after {@code FILTER_FIELD_UNKNOWN}, which says the same
         * thing one level along, and refused rather than dropped: a field the
         * service silently discards is one a client comes to depend on, and
         * the caller is told its call succeeded. Measured as F-0365.
         */
        BODY_FIELD_UNKNOWN(400);

        private final int status;

        Reason(int status) {
            this.status = status;
        }

        /** The HTTP status this reason answers with, fixed at the reason. */
        public int status() {
            return status;
        }
    }

    private final transient Reason reason;
    private final transient String allow;
    private final transient String subject;

    public SurfaceException(Reason reason, String message) {
        this(reason, message, null);
    }

    /**
     * A refusal that names the value it is about.
     *
     * <p>A separate factory rather than a fourth constructor argument, because
     * the {@code allow} overload already occupies the three-argument shape and
     * a second one taking a {@code String} in that position would be decided
     * by whichever cast the call site happened to write.
     *
     * <p>It exists for the refusal whose whole content is a name: a body field
     * the call does not read. Telling the caller "a field you sent is unknown"
     * without saying which one is the sentence the typed refusal model exists
     * to replace.
     */
    public static SurfaceException about(Reason reason, String subject, String message) {
        return new SurfaceException(reason, message, null, subject);
    }

    /**
     * @param allow the {@code Allow} header value, where the status requires
     *              one. 405 without it is a refusal that does not say what
     *              would have worked.
     */
    public SurfaceException(Reason reason, String message, String allow) {
        this(reason, message, allow, null);
    }

    private SurfaceException(Reason reason, String message, String allow, String subject) {
        super(message);
        this.reason = reason;
        this.allow = allow;
        this.subject = subject;
    }

    public Reason reason() {
        return reason;
    }

    public String allow() {
        return allow;
    }

    /** The value this refusal is about, or null where it names none. */
    public String subject() {
        return subject;
    }
}
