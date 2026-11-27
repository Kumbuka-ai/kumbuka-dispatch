/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import java.util.List;
import java.util.stream.Stream;

/**
 * The calls on a task this service carries so far, each with the one
 * permission it needs (REA-0010, catalogues "Calls that are not transitions"
 * and "Transitions of a task").
 *
 * <p>Which of them are open to a caller is decided here, from the task's
 * state as a reader is told it, whether the caller holds it, and the caller's
 * permissions, and nothing else: {@code update}, {@code delete} and {@code
 * send} apply to a draft alone; {@code claim} to an open task whose deferral,
 * if it carries one, has come; {@code renew}, {@code release} and {@code defer}
 * to an active task, for its holder. The calls a later order binds arrive with
 * it. The surface names them in its own vocabulary.
 */
public enum TaskCall {

    UPDATE(DispatchPermission.TASK_COMMISSION),
    DELETE(DispatchPermission.TASK_COMMISSION),
    SEND(DispatchPermission.TASK_COMMISSION),
    CLAIM(DispatchPermission.TASK_EXECUTE),
    RENEW(DispatchPermission.TASK_EXECUTE),
    RELEASE(DispatchPermission.TASK_EXECUTE),
    DEFER(DispatchPermission.TASK_EXECUTE);

    private final DispatchPermission permission;

    TaskCall(DispatchPermission permission) {
        this.permission = permission;
    }

    /** The permission the call needs. */
    public DispatchPermission permission() {
        return permission;
    }

    /**
     * The calls open to an actor on a task.
     *
     * @param state           the task's state as a reader is told it
     * @param holder          whether the actor holds the task under a running lease
     * @param deferralPending whether the task is deferred until an instant that has not come
     */
    static List<TaskCall> openTo(Actor actor, String state, boolean holder,
                                 boolean deferralPending) {
        Stream<TaskCall> open = switch (state) {
            case Task.DRAFT -> Stream.of(UPDATE, DELETE, SEND);
            case Task.OPEN -> deferralPending ? Stream.empty() : Stream.of(CLAIM);
            case Task.ACTIVE -> holder ? Stream.of(RENEW, RELEASE, DEFER) : Stream.empty();
            default -> Stream.empty();
        };
        return open.filter(call -> actor.holds(call.permission)).toList();
    }
}
