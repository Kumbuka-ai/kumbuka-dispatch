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
 * The calls on a task this service carries so far, each with the one
 * permission it needs (REA-0010, catalogues "Calls that are not transitions"
 * and "Transitions of a task").
 *
 * <p>Which of them are open to a caller is decided here, from the task's
 * state and the caller's permissions, and nothing else: {@code update},
 * {@code delete} and {@code send} apply to a draft alone. The calls on a sent
 * task arrive with the orders that bind them. The surface names them in its
 * own vocabulary.
 */
public enum TaskCall {

    UPDATE(DispatchPermission.TASK_COMMISSION),
    DELETE(DispatchPermission.TASK_COMMISSION),
    SEND(DispatchPermission.TASK_COMMISSION);

    private final DispatchPermission permission;

    TaskCall(DispatchPermission permission) {
        this.permission = permission;
    }

    /** The permission the call needs. */
    public DispatchPermission permission() {
        return permission;
    }

    /** The calls open to an actor on a task in a state: those of a draft, on a draft alone. */
    static List<TaskCall> openTo(Actor actor, boolean draft) {
        if (!draft) {
            return List.of();
        }
        return Arrays.stream(values()).filter(call -> actor.holds(call.permission)).toList();
    }
}
