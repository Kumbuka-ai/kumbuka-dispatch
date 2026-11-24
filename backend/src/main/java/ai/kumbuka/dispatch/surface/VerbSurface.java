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
import ai.kumbuka.dispatch.domain.ExternalReference;
import ai.kumbuka.dispatch.domain.Instance;
import ai.kumbuka.dispatch.domain.InstanceAddress;
import ai.kumbuka.dispatch.domain.InstanceService;
import ai.kumbuka.dispatch.domain.InstanceView;
import ai.kumbuka.dispatch.domain.Provenance;
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
 * {@link ProcessVerb}; a call is never offered before it is bound here.
 * {@code read_definition} writes nothing, and so writes no log line of a
 * completed write. Every act on an instance writes one, naming the call, the
 * instance's technical address and the scope's id, and nothing a caller or a
 * tenant chose (C13.3, D23). The lines of the refusals carry their codes
 * alone, so no title, label, field name, value or text reaches the log
 * (DEC-0051).
 *
 * <p>Every act a call writes here records where it comes from, derived from
 * the verified identity and never from the call: the subject of the token as
 * its actor (D17), the one permission the call checked, the path {@code
 * interface}, and no authentication level, which a call over an interface
 * does not carry. Until delegated identities exist, identity and owner
 * coincide, and the act names no owner beside its actor (REA-0010 section 1).
 */
@ApplicationScoped
@TenantBound
public class VerbSurface {

    @Inject DefinitionService definitions;
    @Inject InstanceService instances;
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
        UUID scopeId = resolve(actor, scope, Access.READ).scopeId();
        return definitions.read(scopeId, selector, version);
    }

    /** {@code start}: an instance of a selector, bound to the version in force. */
    @Transactional
    public InstanceView start(Actor actor, String scope, String selector,
                              InstanceService.Start start) {
        ScopeDirectory.ScopeAccess resolved = resolve(actor, scope, Access.WRITE);
        return written(ProcessVerb.START, resolved, instances.start(actor, scope,
            resolved.scopeId(), selector, start, provenance(actor, ProcessVerb.START)));
    }

    /** {@code conclude} and {@code withdraw}: closes a running instance with its return. */
    @Transactional
    public InstanceView close(Actor actor, ProcessVerb verb, InstanceAddress address,
                              InstanceService.Closing closing) {
        Instance.Resolution how = switch (verb) {
            case CONCLUDE -> Instance.Resolution.CONCLUDED;
            case WITHDRAW -> Instance.Resolution.WITHDRAWN;
            default -> throw new IllegalArgumentException(verb.call() + " closes no instance");
        };
        ScopeDirectory.ScopeAccess resolved = resolve(actor, address.scope(), Access.WRITE);
        return written(verb, resolved, instances.close(actor, address, resolved.scopeId(), how,
            closing, provenance(actor, verb)));
    }

    /** {@code reference_attach}: sets a reference of a running instance. */
    @Transactional
    public InstanceView attach(Actor actor, InstanceAddress address,
                               ExternalReference.Kind kind, String value) {
        ScopeDirectory.ScopeAccess resolved = resolve(actor, address.scope(), Access.WRITE);
        return written(ProcessVerb.REFERENCE_ATTACH, resolved, instances.attach(actor, address,
            resolved.scopeId(), kind, value, provenance(actor, ProcessVerb.REFERENCE_ATTACH)));
    }

    /** {@code reference_detach}: ends a reference of a running instance. */
    @Transactional
    public InstanceView detach(Actor actor, InstanceAddress address,
                               ExternalReference.Kind kind, String value) {
        ScopeDirectory.ScopeAccess resolved = resolve(actor, address.scope(), Access.WRITE);
        return written(ProcessVerb.REFERENCE_DETACH, resolved, instances.detach(actor, address,
            resolved.scopeId(), kind, value, provenance(actor, ProcessVerb.REFERENCE_DETACH)));
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
     * Resolves a scope a writing call names, and makes no call: as {@link
     * #resolveScope}, and besides refuses a locked scope before the caller's
     * permission is checked, since a lock is the scope's state and holds for
     * every caller (C8.7, {@code SCOPE_LOCKED} before {@code
     * ROLE_INSUFFICIENT}).
     */
    @Transactional
    public void resolveScopeToWrite(Actor actor, String scope) {
        resolve(actor, scope, Access.WRITE);
    }

    /**
     * The one way to a scope: the platform's directory decides whether the
     * caller may see it and, for a write, whether it may write to it.
     */
    private ScopeDirectory.ScopeAccess resolve(Actor actor, String scope, Access access) {
        return scopes.resolve(actor.subject(), scope, access);
    }

    /** The line of a completed write, and the instance it wrote. */
    private static InstanceView written(ProcessVerb verb, ScopeDirectory.ScopeAccess scope,
                                        InstanceView instance) {
        PlatformLog.written(VerbName.of(verb, Surface.MCP),
            PlatformLog.TechnicalAddress.of(instance), PlatformLog.ScopeId.of(scope));
        return instance;
    }

    /** Where the acts of a call come from: its actor, its permission, its path. */
    private static Provenance provenance(Actor actor, ProcessVerb verb) {
        return new Provenance(actor.subject(), null, verb.permissions().get(0),
            Provenance.Path.INTERFACE, null);
    }
}
