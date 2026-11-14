/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.mcp;

import ai.kumbuka.dispatch.adapter.payload.CallBinding;
import ai.kumbuka.dispatch.adapter.payload.DeclaredCalls;
import ai.kumbuka.dispatch.domain.DispatchPermission;
import ai.kumbuka.dispatch.surface.VerbSurface;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.security.PermissionsAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The tools of the assistant surface, one method each, and the one permission
 * each checks about its caller (DEC-0052): the method makes its call only for
 * a caller holding it, and checks nothing else about the caller. Where a
 * method names two, the caller holds either: {@code dispatch_read_text} and
 * {@code dispatch_annotate} ({@code ProcessVerb#permissions}).
 *
 * <p>{@link McpAdapter} reads a tool call into a prepared call and hands it
 * here through {@link CallBinding#through}. A caller without the permission
 * is refused before the call is made, and the adapter answers it after the
 * call's scopes, as every refusal of the caller's part. Present where the
 * adapter is (C4.6).
 */
@IfBuildProperty(name = "kumbuka.local-mcp.enabled", stringValue = "true", enableIfMissing = true)
@ApplicationScoped
public class ToolCalls implements DeclaredCalls {

    @Inject VerbSurface verbs;

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_COMMISSION)
    public CallBinding.Outcome send(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_COMMISSION)
    public CallBinding.Outcome recall(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_EXECUTE)
    public CallBinding.Outcome claim(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_EXECUTE)
    public CallBinding.Outcome release(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_EXECUTE)
    public CallBinding.Outcome defer(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_EXECUTE)
    public CallBinding.Outcome renew(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_EXECUTE)
    public CallBinding.Outcome ask(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_DECIDE)
    public CallBinding.Outcome answer(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_EXECUTE)
    public CallBinding.Outcome hold(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_EXECUTE)
    public CallBinding.Outcome resume(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_EXECUTE)
    public CallBinding.Outcome deliver(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_CONFIRM)
    public CallBinding.Outcome rework(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_CONFIRM)
    public CallBinding.Outcome accept(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_EXECUTE)
    public CallBinding.Outcome reject(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_EXECUTE)
    public CallBinding.Outcome fail(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_WITHDRAW)
    public CallBinding.Outcome withdraw(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_COMMISSION)
    public CallBinding.Outcome create(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_COMMISSION)
    public CallBinding.Outcome update(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_COMMISSION)
    public CallBinding.Outcome delete(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.INSTANCE_READ)
    public CallBinding.Outcome read(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed({DispatchPermission.Name.INSTANCE_READ_TEXT,
        DispatchPermission.Name.INSTANCE_READ_TEXT_AS_HOLDER})
    public CallBinding.Outcome readText(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.INSTANCE_READ)
    public CallBinding.Outcome query(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed({DispatchPermission.Name.TASK_COMMISSION,
        DispatchPermission.Name.TASK_EXECUTE})
    public CallBinding.Outcome annotate(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_COMMISSION)
    public CallBinding.Outcome relate(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_COMMISSION)
    public CallBinding.Outcome unrelate(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_TAG)
    public CallBinding.Outcome tagAttach(CallBinding.Prepared call) {
        return call.make(verbs);
    }

    @Override
    @PermissionsAllowed(DispatchPermission.Name.TASK_TAG)
    public CallBinding.Outcome tagDetach(CallBinding.Prepared call) {
        return call.make(verbs);
    }
}
