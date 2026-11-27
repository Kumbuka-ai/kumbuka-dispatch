/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.surface.InstanceWire;
import ai.kumbuka.dispatch.surface.TaskWire;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The locks of {@code create} and {@code send} under concurrency.
 *
 * <p>Two transactions meet in each case. The first is held open in the test
 * thread while the second runs on a thread of its own ({@link Overlap}). The
 * first commits only once the database reports the second waiting on a lock
 * the first holds ({@link LockWaits}), so the overlap is established and not
 * hoped for. Each case then asserts what the store holds once both are done,
 * which is the statement the lock exists for, and that the second did wait.
 *
 * <p>The red state of each case is the read the lock replaces, observed while
 * the first transaction is still open: a read without the lock finds the
 * counter of the instance, or the task, as it stood before the first, so a
 * second transaction deciding on such a read would take the sub the first
 * took, or send the draft the first sent.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskDraftLockIT {

    private static final Actor CONSOLE = new Actor(InstanceWire.SUBJECT,
        DispatchPermission.TASK_COMMISSION, DispatchPermission.INSTANCE_START);
    private static final Provenance PROVENANCE = new Provenance(InstanceWire.SUBJECT, null,
        DispatchPermission.TASK_COMMISSION, Provenance.Path.INTERFACE, null);

    @Inject TaskService tasks;
    @Inject InstanceService instances;
    @Inject TenantContext tenantContext;
    @Inject EntityManager em;

    @Test
    void two_creates_in_one_instance_take_different_consecutive_subs() throws Exception {
        String selector = TaskWire.plant("subs", "review").selector();
        InstanceAddress instance = start(selector);

        Overlap overlap = stage().run(() -> {
            TaskView first = create(instance);
            assertThat(first.address().sub()).isEqualTo(1);
            assertThat(InstanceWire.single("SELECT next_sub FROM dispatch.instance WHERE id = ?",
                    InstanceWire.instanceId(instance.complete())))
                .as("RED STATE, observed: while the first create is open, a read of the counter "
                    + "without the lock still finds the sub the first took")
                .isEqualTo(1);
            return first;
        }, () -> create(instance));

        assertThat(overlap.waited())
            .as("the second create waited on the lock the first held on the instance's row")
            .isTrue();
        assertThat(List.of(((TaskView) overlap.first()).address().sub(),
                ((TaskView) overlap.second()).address().sub()))
            .as("the two creates took different subs, one after the other, without a gap")
            .containsExactly(1, 2);
        assertThat(InstanceWire.single("SELECT next_sub FROM dispatch.instance WHERE id = ?",
                InstanceWire.instanceId(instance.complete())))
            .as("and the counter stands after both").isEqualTo(3);
    }

    @Test
    void two_sends_of_one_draft_at_once_send_it_exactly_once() throws Exception {
        String selector = TaskWire.plant("sends", "review").selector();
        TaskView draft = create(start(selector));

        Overlap overlap = stage().run(() -> {
            TaskView sent = send(draft);
            assertThat(InstanceWire.rows("SELECT state FROM dispatch.task WHERE id = ?",
                    draft.id()))
                .as("RED STATE, observed: while the first send is open, a read without the "
                    + "lock still finds the task in draft, and a send deciding on it would send "
                    + "it a second time")
                .containsExactly(Map.of("state", "draft"));
            return sent;
        }, () -> send(draft));

        assertThat(overlap.waited()).as("the second send waited on the first's lock").isTrue();
        assertThat(((TaskView) overlap.first()).state()).isEqualTo("open");
        assertThat(overlap.second())
            .as("the second found the task sent and was refused")
            .isInstanceOfSatisfying(DispatchException.class, refused ->
                assertThat(refused.reason())
                    .isEqualTo(DispatchException.Reason.TRANSITION_NOT_PERMITTED));
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.act WHERE task_id = ? "
                + "AND kind = 'send'", draft.id()))
            .as("exactly one send act stands").isEqualTo(1);
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.commission c "
                + "JOIN dispatch.act a ON a.id = c.act_id WHERE a.task_id = ?", draft.id()))
            .as("and exactly one commission").isEqualTo(1);
    }

    // -----------------------------------------------------------------------

    private Overlap.Stage stage() {
        return new Overlap.Stage(em, tenantContext, InstanceWire.TENANT);
    }

    private InstanceAddress start(String selector) {
        return instances.start(CONSOLE, InstanceWire.SLUG, InstanceWire.SCOPE, selector,
            new InstanceService.Start("an instance", null, null, List.of(), List.of(), Map.of()),
            new Provenance(InstanceWire.SUBJECT, null, DispatchPermission.INSTANCE_START,
                Provenance.Path.INTERFACE, null)).address();
    }

    private TaskView create(InstanceAddress instance) {
        return tasks.create(CONSOLE, InstanceWire.SLUG, InstanceWire.SCOPE, instance.selector(),
            new TaskService.Create(instance, new TaskService.Drafting("a title", null, null, null,
                List.of(), null, null), null, List.of(), IdempotencyKey.NONE), PROVENANCE);
    }

    private TaskView send(TaskView draft) {
        return tasks.send(CONSOLE, draft.address(), InstanceWire.SCOPE, draft.conflictToken(),
            PROVENANCE);
    }
}
