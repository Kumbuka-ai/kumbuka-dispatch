package ai.kumbuka.dispatch.api;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Derives the calling actor from the token, and from nothing else.
 *
 * <p>The subject is the token's stable subject and the capacity comes from the
 * realm roles. Neither is ever read from a request body: authorship is
 * server-derived from the write channel, so a surface that accepted an actor
 * would let a caller sign somebody else's name to a transition.
 *
 * <p>A caller carrying neither role gets no default. Defaulting the capacity
 * would decide a permission by omission, and the two permissions hanging off
 * this capacity — the ratification bolt and the withheld body — are the two
 * that are bolts rather than conveniences. A token that authenticated but
 * carries no capacity is therefore a refusal and not a weaker actor.
 *
 * <p>Both roles at once is refused as well. It is not a superset: an identity
 * that is an executor <em>and</em> a console would pass the ratification bolt
 * while also being the thing the bolt exists to stop.
 */
@RequestScoped
public class CallerActor {

    /**
     * The one refusal on this surface that is worth WARN, and the reason it
     * is the exception.
     *
     * <p>Every other refusal here is a caller being told no, which is the
     * surface working; those are DEBUG in {@link RefusalMapper}. This one is
     * not about the call at all. A token that authenticated and carries no
     * capacity — or both — is a <strong>realm misconfiguration</strong>: the
     * roles are wrong, no caller can fix it, and nothing else will say so. The
     * caller sees a 403 and falls silent, and the roles stay wrong.
     *
     * <p>Neither the subject nor the capacity is logged. Which token it was
     * belongs to the audit log under its own rules; what the operator needs
     * from this line is that the realm is handing out unusable tokens at all.
     */
    private static final Logger LOG = Logger.getLogger(CallerActor.class);

    @Inject SecurityIdentity identity;

    /**
     * @return the actor this request acts as
     * @throws DispatchException with {@code ACTOR_UNKNOWN} when the token
     *         carries no capacity, or carries both
     */
    public Actor current() {
        boolean executor = identity.hasRole(Actor.ROLE_EXECUTOR);
        boolean console = identity.hasRole(Actor.ROLE_CONSOLE);

        if (executor == console) {
            LOG.warnf("a token authenticated with an unusable capacity (holds both: %s): %s",
                executor, DispatchException.Reason.ACTOR_UNKNOWN);
            throw new DispatchException(DispatchException.Reason.ACTOR_UNKNOWN,
                executor
                    ? ("this token carries both '" + Actor.ROLE_EXECUTOR + "' and '"
                        + Actor.ROLE_CONSOLE + "'. The two are capacities and not levels: "
                        + "an identity holding both would pass the ratification bolt while "
                        + "also being what the bolt exists to stop.")
                    : ("this token carries neither '" + Actor.ROLE_EXECUTOR + "' nor '"
                        + Actor.ROLE_CONSOLE + "'. The capacity is not defaulted, because "
                        + "the two guarantees hanging off it are permissions rather than "
                        + "conveniences and one of them would be decided by omission."));
        }

        return new Actor(
            identity.getPrincipal().getName(),
            executor ? Actor.Kind.EXECUTOR : Actor.Kind.CONSOLE);
    }
}
