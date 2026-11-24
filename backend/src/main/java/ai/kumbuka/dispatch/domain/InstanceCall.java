/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import java.util.Arrays;
import java.util.List;

/**
 * The calls on a running instance, each with the one permission it needs
 * (REA-0010, catalogue "Acts on an instance").
 *
 * <p>Which of them are open to a caller is decided here, from the instance's
 * state and the caller's permissions, and nothing else: a closed instance
 * takes none. The surface names them in its own vocabulary.
 */
public enum InstanceCall {

    CONCLUDE(DispatchPermission.INSTANCE_CONCLUDE),
    WITHDRAW(DispatchPermission.INSTANCE_WITHDRAW),
    REFERENCE_ATTACH(DispatchPermission.INSTANCE_REFER),
    REFERENCE_DETACH(DispatchPermission.INSTANCE_REFER);

    private final DispatchPermission permission;

    InstanceCall(DispatchPermission permission) {
        this.permission = permission;
    }

    /** The permission the call needs. */
    public DispatchPermission permission() {
        return permission;
    }

    /** The calls open to an actor on an instance: none once it is closed. */
    static List<InstanceCall> openTo(Actor actor, boolean running) {
        if (!running) {
            return List.of();
        }
        return Arrays.stream(values()).filter(call -> actor.holds(call.permission)).toList();
    }
}
