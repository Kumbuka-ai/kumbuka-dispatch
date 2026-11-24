/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchPermission;
import ai.kumbuka.dispatch.surface.VerbSurface;
import io.quarkus.security.PermissionsAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The declared calls, one method each, and the one permission each checks
 * about its caller (DEC-0052, ADR-0053/C10): the method makes its call only
 * for a caller holding it, and checks nothing else about the caller.
 *
 * <p>One method per call rather than one for all, because the permission is
 * the call's: a single method would check one for every call or none. Both
 * adapters make every call here, once they have checked its form and resolved
 * the scope it names; it is the part of the adapter both surfaces share, as
 * the form check is (C1.2, C27.1). A caller without the permission is refused
 * before the call is made, with the security layer's exception, which the
 * adapter's edge answers as {@code ROLE_INSUFFICIENT}: the scope is resolved
 * by then, so a scope the caller may not see has already answered {@code
 * NOT_FOUND} (C1.3).
 */
@ApplicationScoped
public class DeclaredCalls {

    @Inject VerbSurface verbs;

    /** {@code read_definition}, under {@code dispatch.instance.read}. */
    @PermissionsAllowed(DispatchPermission.Name.INSTANCE_READ)
    public DefinitionPayload readDefinition(Actor actor, CallForm.ReadDefinition call) {
        return Payloads.definition(
            verbs.readDefinition(actor, call.scope(), call.selector(), call.version()));
    }
}
