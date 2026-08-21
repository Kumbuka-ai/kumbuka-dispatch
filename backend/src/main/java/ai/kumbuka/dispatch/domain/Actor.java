package ai.kumbuka.dispatch.domain;

/**
 * Who is calling, and in what capacity.
 *
 * <p>Two capacities, because two of this service's guarantees are permissions
 * bound to the caller rather than conveniences: the executing apparatus cannot
 * ratify, and it cannot obtain the body of a commission it has not claimed.
 * Both live in the core. An adapter that simply did not project those paths
 * would be positioning, not enforcement — anything that must hold has to hold
 * for every caller that reaches the core, including the next adapter nobody
 * has written yet.
 *
 * <h2>Where the capacity comes from, and what is assumed</h2>
 *
 * From the realm roles the service already reads off the access token. Nothing
 * new is invented here: the identity provider is the same one the service is
 * bound to, and the claim path is the one already configured.
 *
 * <p>What IS an assumption, and is recorded as one rather than buried: that
 * the two roles below are the names the realm will carry. The identity of an
 * individual agent is a separate and deliberately open question — this
 * distinguishes a KIND of caller, not which agent is calling, and nothing here
 * depends on being able to tell two executors apart.
 *
 * @param subject the token's stable subject. Authorship is derived from it and
 *                never accepted from a caller.
 * @param kind    the capacity the caller holds
 */
public record Actor(String subject, Kind kind) {

    /** The realm role that marks an executing apparatus. */
    public static final String ROLE_EXECUTOR = "dispatch-executor";
    /** The realm role that marks a human-facing console identity. */
    public static final String ROLE_CONSOLE = "dispatch-console";

    public enum Kind {
        /**
         * An apparatus that takes commissions up and performs them. It may
         * claim, write a handover draft, and terminate what it holds. It may
         * not ratify, and it may not read the body of a commission it has not
         * claimed.
         */
        EXECUTOR,
        /**
         * A human-facing identity. It ratifies — that is the operator's own
         * act — and it may read a commission body without holding the claim,
         * because operators read and edit handovers as a matter of course.
         */
        CONSOLE
    }

    public Actor {
        if (subject == null || subject.isBlank()) {
            throw new DispatchException(DispatchException.Reason.ACTOR_UNKNOWN,
                "an actor must carry a subject. Authorship is derived from the token and "
                    + "never accepted from a caller, so a call with no subject has nobody "
                    + "to attribute and cannot be recorded.");
        }
        if (kind == null) {
            throw new DispatchException(DispatchException.Reason.ACTOR_UNKNOWN,
                "an actor must carry a capacity. Defaulting one would decide a permission "
                    + "by omission, and the two permissions that hang off this are the two "
                    + "that carry a bolt rather than a convenience.");
        }
    }

    public boolean isExecutor() {
        return kind == Kind.EXECUTOR;
    }

    public boolean isConsole() {
        return kind == Kind.CONSOLE;
    }
}
