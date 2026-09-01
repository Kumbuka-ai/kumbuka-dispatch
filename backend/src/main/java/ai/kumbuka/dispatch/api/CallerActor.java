package ai.kumbuka.dispatch.api;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

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
