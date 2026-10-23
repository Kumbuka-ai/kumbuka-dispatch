/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.EnumSet;
import java.util.Set;

/**
 * Derives the calling actor from the token, and from nothing else.
 *
 * <p>The subject is the token's stable subject and the capacities come from
 * the realm roles. Neither is ever read from a request body: authorship is
 * server-derived from the write channel, so a surface that accepted an actor
 * would let a caller sign somebody else's name to a transition.
 *
 * <p>A caller carrying neither role gets no default. Defaulting the capacity
 * would decide a permission by omission, and the two permissions hanging off
 * this capacity — the ratification bolt and the withheld body — are the two
 * that are bolts rather than conveniences. A token that authenticated but
 * carries no capacity is therefore a refusal and not a weaker actor.
 *
 * <p>Both roles at once is admitted (TAR-0004 section 3, REQ-0034): the
 * capacities are granted to an identity, not kinds of caller, and the person
 * who works alone holds both. Four-eyes is the grant of the executing role
 * without the commissioning one; an acceptance of its own answer by an
 * identity holding both is marked on the task, not refused.
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
     * capacity is a <strong>realm misconfiguration</strong>: the
     * roles are wrong, no caller can fix it, and nothing else will say so. The
     * caller sees a 403 and falls silent, and the roles stay wrong.
     *
     * <p>The subject is not logged. Which token it was belongs to the audit
     * log under its own rules; what the operator needs from this line is that
     * the realm is handing out unusable tokens at all.
     */
    private static final Logger LOG = Logger.getLogger(CallerActor.class);

    @Inject SecurityIdentity identity;

    /**
     * @return the actor this request acts as
     * @throws DispatchException with {@code ACTOR_UNKNOWN} when the token
     *         carries no capacity
     */
    public Actor current() {
        Set<Actor.Kind> capacities = EnumSet.noneOf(Actor.Kind.class);
        if (identity.hasRole(Actor.ROLE_EXECUTOR)) {
            capacities.add(Actor.Kind.EXECUTOR);
        }
        if (identity.hasRole(Actor.ROLE_CONSOLE)) {
            capacities.add(Actor.Kind.CONSOLE);
        }

        if (capacities.isEmpty()) {
            LOG.warnf("a token authenticated without a capacity: %s",
                DispatchException.Reason.ACTOR_UNKNOWN);
            throw new DispatchException(DispatchException.Reason.ACTOR_UNKNOWN,
                "this token carries neither '" + Actor.ROLE_EXECUTOR + "' nor '"
                    + Actor.ROLE_CONSOLE + "'. The capacity is not defaulted, because "
                    + "the two guarantees hanging off it are permissions rather than "
                    + "conveniences and one of them would be decided by omission.");
        }

        return new Actor(identity.getPrincipal().getName(), capacities);
    }
}
