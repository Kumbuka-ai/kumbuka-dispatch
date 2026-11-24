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
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Surface;
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
 *
 * <p>An act on an instance answers the instance, and a call on a task the
 * task, each with the calls open on it named on the surface the caller came
 * through (C6.2); {@code delete} answers the address the draft had.
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

    /** {@code start}, under {@code dispatch.instance.start}. */
    @PermissionsAllowed(DispatchPermission.Name.INSTANCE_START)
    public InstancePayload start(Actor actor, CallForm.Start call, Surface surface) {
        return Payloads.instance(
            verbs.start(actor, call.scope(), call.selector(), call.start()), surface);
    }

    /** {@code conclude}, under {@code dispatch.instance.conclude}. */
    @PermissionsAllowed(DispatchPermission.Name.INSTANCE_CONCLUDE)
    public InstancePayload conclude(Actor actor, CallForm.Close call, Surface surface) {
        return Payloads.instance(
            verbs.close(actor, ProcessVerb.CONCLUDE, call.address(), call.closing()), surface);
    }

    /** {@code withdraw} of an instance, under {@code dispatch.instance.withdraw}. */
    @PermissionsAllowed(DispatchPermission.Name.INSTANCE_WITHDRAW)
    public InstancePayload withdraw(Actor actor, CallForm.Close call, Surface surface) {
        return Payloads.instance(
            verbs.close(actor, ProcessVerb.WITHDRAW, call.address(), call.closing()), surface);
    }

    /** {@code reference_attach}, under {@code dispatch.instance.refer}. */
    @PermissionsAllowed(DispatchPermission.Name.INSTANCE_REFER)
    public InstancePayload referenceAttach(Actor actor, CallForm.Refer call, Surface surface) {
        return Payloads.instance(
            verbs.attach(actor, call.address(), call.kind(), call.value()), surface);
    }

    /** {@code reference_detach}, under {@code dispatch.instance.refer}. */
    @PermissionsAllowed(DispatchPermission.Name.INSTANCE_REFER)
    public InstancePayload referenceDetach(Actor actor, CallForm.Refer call, Surface surface) {
        return Payloads.instance(
            verbs.detach(actor, call.address(), call.kind(), call.value()), surface);
    }

    /** {@code create}, with and without a parent, under {@code dispatch.task.commission}. */
    @PermissionsAllowed(DispatchPermission.Name.TASK_COMMISSION)
    public TaskPayload create(Actor actor, CallForm.Create call, Surface surface) {
        return Payloads.task(verbs.create(actor, call.scope(), call.selector(), call.create()),
            surface);
    }

    /** {@code update}, under {@code dispatch.task.commission}. */
    @PermissionsAllowed(DispatchPermission.Name.TASK_COMMISSION)
    public TaskPayload update(Actor actor, CallForm.Update call, Surface surface) {
        return Payloads.task(verbs.update(actor, call.address(), call.update()), surface);
    }

    /** {@code delete}, under {@code dispatch.task.commission}. */
    @PermissionsAllowed(DispatchPermission.Name.TASK_COMMISSION)
    public AddressPayload delete(Actor actor, CallForm.OnADraft call) {
        return Payloads.deleted(verbs.delete(actor, call.address(), call.conflictToken()));
    }

    /** {@code send}, under {@code dispatch.task.commission}. */
    @PermissionsAllowed(DispatchPermission.Name.TASK_COMMISSION)
    public TaskPayload send(Actor actor, CallForm.OnADraft call, Surface surface) {
        return Payloads.task(verbs.send(actor, call.address(), call.conflictToken()), surface);
    }
}
