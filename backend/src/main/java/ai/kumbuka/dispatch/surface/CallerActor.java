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
import ai.kumbuka.dispatch.domain.DispatchPermission;
import io.quarkus.security.StringPermission;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import java.util.EnumSet;
import java.util.Set;

/**
 * Derives the calling actor from the verified identity, and from nothing else.
 *
 * <p>The subject is the token's stable subject, and the permissions are the
 * ones the identity holds of this service's catalogue, whoever handed them to
 * it. No role is read here (DEC-0052). Neither is ever read from a request
 * body: authorship is server-derived from the write channel, so a surface that
 * accepted an actor would let a caller sign somebody else's name to a
 * transition.
 *
 * <p>An identity holding no permission of this service gets no default.
 * Defaulting one would decide a permission by omission. A token that
 * authenticated and holds none is therefore a refusal and not a weaker actor.
 */
@RequestScoped
public class CallerActor {

    @Inject SecurityIdentity identity;

    /**
     * The actor of this request.
     *
     * <p>The one refusal on this surface that is worth WARN, and the reason
     * it is the exception: every other refusal is a caller being told no,
     * which is the surface working, and is DEBUG where it is caught. This one
     * is not about the call at all. A token that authenticated and holds no
     * permission of this service is a <strong>realm misconfiguration</strong>:
     * the grants are wrong, no caller can fix it, and nothing else will say
     * so. The subject is not logged; which token it was belongs to the audit
     * log under its own rules.
     *
     * @return the actor this request acts as
     * @throws DispatchException with {@code ACTOR_UNKNOWN} when the identity
     *         holds no permission of this service
     */
    public Actor current() {
        Set<DispatchPermission> held = EnumSet.noneOf(DispatchPermission.class);
        for (DispatchPermission permission : DispatchPermission.values()) {
            if (identity.checkPermissionBlocking(new StringPermission(permission.wireName()))) {
                held.add(permission);
            }
        }

        if (held.isEmpty()) {
            PlatformLog.tokenWithoutCapacity(
                PlatformLog.Reason.of(DispatchException.Reason.ACTOR_UNKNOWN));
            throw new DispatchException(DispatchException.Reason.ACTOR_UNKNOWN,
                "this identity holds no permission of this service. None is defaulted, "
                    + "because a permission decided by omission is no permission.");
        }

        return new Actor(identity.getPrincipal().getName(), held);
    }
}
