package ai.kumbuka.dispatch.surface;

/**
 * A typed refusal the surface itself produces, as distinct from one the domain
 * produces.
 *
 * <p>The two are kept apart deliberately. {@code DispatchException} says
 * something about a task — its state, its holder, its texts — and the
 * domain is the only place that knows those things. This one says something
 * about the <em>call</em>: the address does not parse, the scheme does not
 * carry the verb, a writing verb arrived on a collection. None of that needs
 * a task to exist, and it is decided before one is looked for.
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
         * The address violates the production. Decidable without knowing any
         * scope, which is why answering it leaks nothing and why this check
         * sits in front of everything else.
         */
        ADDRESS_MALFORMED(400);

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

    public SurfaceException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
