/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.surface.InstanceWire;
import ai.kumbuka.dispatch.surface.TaskWire;

import java.util.List;
import java.util.Map;

/**
 * What the integration tests of the holding of a task share: a task sent and
 * open, made through the kernel as a caller's calls make it, the callers that
 * take part, and the store read and moved by SQL as its administrator.
 *
 * <p>Every expectation stays with the test that uses this. The version is
 * planted row by row ({@link TaskWire#plant}), the task is created and sent by
 * the kernel of T4, and a lease is let run out by a write of its end into the
 * past, as the clock would let it.
 */
final class HoldingFixture {

    /** The commissioner, who starts the instance and sends the task. */
    static final Actor COMMISSIONER = new Actor(InstanceWire.SUBJECT,
        DispatchPermission.TASK_COMMISSION, DispatchPermission.INSTANCE_START);

    /** An executor, who takes tasks up. */
    static final Actor EXECUTOR = new Actor("executor-one", DispatchPermission.TASK_EXECUTE);

    /** Another executor. */
    static final Actor OTHER = new Actor("executor-two", DispatchPermission.TASK_EXECUTE);

    private HoldingFixture() {
    }

    /** Where the acts of a caller come from, under the permission named. */
    static Provenance provenance(Actor actor, DispatchPermission permission) {
        return new Provenance(actor.subject(), null, permission, Provenance.Path.INTERFACE, null);
    }

    /** Where the acts of an executor's call come from. */
    static Provenance executing(Actor actor) {
        return provenance(actor, DispatchPermission.TASK_EXECUTE);
    }

    /**
     * A task created and sent in a fresh instance of a fresh selector, open
     * for an executor to take it up; answers its address.
     */
    static TaskAddress sent(InstanceService instances, TaskService tasks, String prefix) {
        String selector = TaskWire.plant(prefix, "review").selector();
        InstanceAddress instance = instances.start(COMMISSIONER, InstanceWire.SLUG,
            InstanceWire.SCOPE, selector, new InstanceService.Start("an instance", null, null,
                List.of(), List.of(), Map.of()),
            provenance(COMMISSIONER, DispatchPermission.INSTANCE_START)).address();
        Provenance commissioning = provenance(COMMISSIONER, DispatchPermission.TASK_COMMISSION);
        TaskView draft = tasks.create(COMMISSIONER, InstanceWire.SLUG, InstanceWire.SCOPE,
            selector, new TaskService.Create(instance, new TaskService.Drafting("a title", null,
                null, null, List.of(), null, null), null, List.of(), IdempotencyKey.NONE),
            commissioning);
        return tasks.send(COMMISSIONER, draft.address(), InstanceWire.SCOPE, draft.conflictToken(),
            commissioning).address();
    }

    /** A claim of the task by an executor, with the default duration and no key. */
    static TaskView claim(HoldingService holdings, Actor actor, TaskAddress task) {
        return holdings.claim(actor, task, InstanceWire.SCOPE,
            new HoldingService.Claim(null, IdempotencyKey.NONE), executing(actor));
    }

    /** The surrogate of the task an address names. */
    static long taskId(TaskAddress task) {
        return TaskWire.taskId(task.complete());
    }

    /** How many rows of a table of the holding hang on a task. */
    static long rows(String table, TaskAddress task) {
        long id = taskId(task);
        return switch (table) {
            case "holding" -> InstanceWire.single(
                "SELECT count(*) FROM dispatch.holding WHERE task_id = ?", id);
            case "receipt", "lease" -> InstanceWire.single("SELECT count(*) FROM dispatch."
                + table + " x JOIN dispatch.holding h ON h.id = x.holding_id WHERE h.task_id = ?",
                id);
            default -> TaskWire.rowsOf(table, id);
        };
    }

    /** The row of the task, as the store holds it: its state, deferral and park. */
    static Map<String, Object> taskRow(TaskAddress task) {
        return InstanceWire.rows("SELECT state, hold_reason, not_before, parked FROM dispatch.task "
            + "WHERE id = ?", taskId(task)).get(0);
    }

    /** Every holding of the task, oldest first, as the store holds it. */
    static List<Map<String, Object>> holdingRows(TaskAddress task) {
        return InstanceWire.rows("SELECT id, claim_act_id, ended_by_act_id FROM dispatch.holding "
            + "WHERE task_id = ? ORDER BY id", taskId(task));
    }

    /** Lets every lease of the task run out, as the clock would: its end a minute ago. */
    static void lapse(TaskAddress task) {
        PlatformFixture.run("UPDATE dispatch.lease SET expires_at = now() - interval '1 minute' "
            + "WHERE holding_id IN (SELECT id FROM dispatch.holding WHERE task_id = "
            + taskId(task) + ")");
    }
}
