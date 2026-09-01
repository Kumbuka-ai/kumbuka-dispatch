package ai.kumbuka.dispatch.api;

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

        /** A field write arrived without the conflict token it declares. */
        CONFLICT_TOKEN_MISSING(428),

        /** The conflict token presented is not the one the object holds. */
        CONFLICT_TOKEN_STALE(412),

        /** The request body is absent or does not carry what the verb needs. */
        PAYLOAD_MALFORMED(400);

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

    public SurfaceException(Reason reason, String message) {
        this(reason, message, null);
    }

    /**
     * @param allow the {@code Allow} header value, where the status requires
     *              one. 405 without it is a refusal that does not say what
     *              would have worked.
     */
    public SurfaceException(Reason reason, String message, String allow) {
        super(message);
        this.reason = reason;
        this.allow = allow;
    }

    public Reason reason() {
        return reason;
    }

    public String allow() {
        return allow;
    }
}
