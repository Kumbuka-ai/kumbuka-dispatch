/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DefinitionService;
import ai.kumbuka.dispatch.domain.DefinitionView;
import ai.kumbuka.dispatch.platform.Access;
import ai.kumbuka.dispatch.platform.ScopeDirectory;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * The one entry of both surfaces into the kernel (C1.1).
 *
 * <p>The adapter reads a call, checks its form against the declaration and
 * calls one method of this class, which resolves the scope the caller names
 * and makes exactly one call of the kernel in one transaction (C1.4). Nothing
 * here decides whether an act is permitted, and nothing here composes two
 * kernel calls into one act.
 *
 * <p>Each call enters this class in the same change that declares it in
 * {@link ProcessVerb}; a call is never offered before it is bound here. The
 * one bound so far, {@code read_definition}, writes nothing, and so writes no
 * log line of a completed write (C13.3); the lines of its refusals carry their
 * codes alone, so no title, label, field name or value of an enumeration of a
 * definition reaches the log (DEC-0051).
 */
@ApplicationScoped
@TenantBound
public class VerbSurface {

    @Inject DefinitionService definitions;
    @Inject ScopeDirectory scopes;

    /**
     * A seeded version of a selector's definition, behind the scope it names:
     * a scope the caller may not see answers {@code NOT_FOUND} as an absent
     * definition does.
     *
     * @param version the number of the version; absent for the active one
     */
    @Transactional
    public DefinitionView readDefinition(Actor actor, String scope, String selector,
                                         @Nullable Integer version) {
        UUID scopeId = resolve(actor, scope, Access.READ);
        return definitions.read(scopeId, selector, version);
    }

    /**
     * Resolves a scope a call names, for reading, and makes no call: for the
     * check order, which answers a scope the caller may not see before the
     * caller's permission (C1.3). The adapter resolves the scope through here
     * before it makes the call under the call's permission, so that a caller
     * without the permission meets {@code NOT_FOUND} for a scope it may not
     * see and {@code ROLE_INSUFFICIENT} only for one it may.
     */
    @Transactional
    public void resolveScope(Actor actor, String scope) {
        resolve(actor, scope, Access.READ);
    }

    /**
     * The one way to a scope: the platform's directory decides whether the
     * caller may see it and, for a write, whether it may write to it.
     */
    private UUID resolve(Actor actor, String scope, Access access) {
        return scopes.resolve(actor.subject(), scope, access).scopeId();
    }
}
