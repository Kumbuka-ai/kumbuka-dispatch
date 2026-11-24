/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.InstanceRepository;
import ai.kumbuka.dispatch.surface.InstanceWire;
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
 * The locks of the acts on an instance under concurrency, rebuilt from the
 * locks of the bracket of the old kernel onto the instance.
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
 * counter, the instance or the task as it stood before the first, so a second
 * transaction deciding on such a read would take the number the first took,
 * close the instance the first closed, or withdraw the task the first closed.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskBracketLockIT {

    private static final String STATE = "state";

    private static final Actor CONSOLE = new Actor(InstanceWire.SUBJECT,
        DispatchPermission.INSTANCE_START, DispatchPermission.INSTANCE_CONCLUDE,
        DispatchPermission.INSTANCE_WITHDRAW);

    @Inject InstanceService instances;
    @Inject InstanceRepository repository;
    @Inject TenantContext tenantContext;
    @Inject EntityManager em;

    @Test
    void two_starts_at_once_take_different_consecutive_numbers() throws Exception {
        String selector = InstanceWire.plant("numbers").selector();

        Overlap overlap = stage().run(() -> {
            InstanceView first = start(selector);
            assertThat(InstanceWire.single("SELECT next_number FROM dispatch.selector "
                    + "WHERE name = ?", selector))
                .as("RED STATE, observed: while the first start is open, a read of the counter "
                    + "without the lock still finds the number the first took")
                .isEqualTo(1);
            return first;
        }, () -> start(selector));

        assertThat(overlap.waited())
            .as("the second start waited on the lock the first held on the selector's row")
            .isTrue();
        assertThat(List.of(((InstanceView) overlap.first()).address().number(),
                ((InstanceView) overlap.second()).address().number()))
            .as("the two starts took different numbers, one after the other, without a gap")
            .containsExactly(1L, 2L);
        assertThat(InstanceWire.single("SELECT next_number FROM dispatch.selector WHERE name = ?",
                selector))
            .as("and the counter stands after both").isEqualTo(3);
    }

    @Test
    void conclude_and_withdraw_at_once_close_the_instance_exactly_once() throws Exception {
        InstanceView started = start(InstanceWire.plant("closings").selector());
        InstanceService.Closing closing = new InstanceService.Closing(started.conflictToken(),
            null, "done", null, null, null);

        Overlap overlap = stage().run(() -> {
            InstanceView concluded = close(started, Instance.Resolution.CONCLUDED, closing);
            assertThat(InstanceWire.rows("SELECT state FROM dispatch.instance WHERE id = ?",
                    started.id()))
                .as("RED STATE, observed: while the conclude is open, a read without the lock "
                    + "still finds the instance running")
                .containsExactly(Map.of(STATE, "running"));
            return concluded;
        }, () -> close(started, Instance.Resolution.WITHDRAWN, closing));

        assertThat(overlap.waited()).as("the withdraw waited on the conclude's lock").isTrue();
        assertThat(((InstanceView) overlap.first()).resolution()).isEqualTo("concluded");
        assertThat(overlap.second())
            .as("the withdraw found the instance closed and was refused")
            .isInstanceOfSatisfying(DispatchException.class, refused ->
                assertThat(refused.reason()).isEqualTo(DispatchException.Reason.INSTANCE_CLOSED));
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.act WHERE instance_id = ? "
                + "AND to_state = 'closed'", started.id()))
            .as("exactly one closing act stands").isEqualTo(1);
        assertThat(InstanceWire.rows("SELECT resolution FROM dispatch.instance WHERE id = ?",
                started.id()))
            .containsExactly(Map.of("resolution", "concluded"));
    }

    @Test
    void a_task_closed_by_a_second_transaction_before_the_closing_keeps_its_state()
            throws Exception {
        InstanceView started = start(InstanceWire.plant("task-race").selector());
        long task = InstanceWire.plantTask(started.id(), 1, "active");
        InstanceService.Closing closing = new InstanceService.Closing(started.conflictToken(),
            null, "done", null, null, null);

        Overlap overlap = stage().run(() -> {
            repository.lockUnfinishedTasks(InstanceWire.SCOPE, started.id());
            em.createNativeQuery("UPDATE dispatch.task SET state = 'closed', "
                    + "resolution = 'accepted' WHERE id = :task")
                .setParameter("task", task)
                .executeUpdate();
            assertThat(InstanceWire.rows("SELECT state FROM dispatch.task WHERE id = ?", task))
                .as("RED STATE, observed: while the first is open, a read without the lock "
                    + "still finds the task unfinished, and a closing deciding on it would "
                    + "withdraw a task the first closed")
                .containsExactly(Map.of(STATE, "active"));
            return task;
        }, () -> close(started, Instance.Resolution.CONCLUDED, closing));

        assertThat(overlap.waited()).as("the closing waited on the lock of the task").isTrue();
        assertThat(overlap.second()).as("and closed without a confirmation, the task finished")
            .isInstanceOf(InstanceView.class);
        assertThat(InstanceWire.rows("SELECT state, resolution FROM dispatch.task WHERE id = ?",
                task))
            .as("the task keeps the resolution the first gave it")
            .containsExactly(Map.of(STATE, "closed", "resolution", "accepted"));
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.act WHERE task_id = ?",
                task))
            .as("and no withdraw was written for it").isZero();
    }

    // -----------------------------------------------------------------------

    private Overlap.Stage stage() {
        return new Overlap.Stage(em, tenantContext, InstanceWire.TENANT);
    }

    private InstanceView start(String selector) {
        return instances.start(CONSOLE, InstanceWire.SLUG, InstanceWire.SCOPE, selector,
            new InstanceService.Start("a title", null, null, List.of(), List.of(),
                Map.of("amount", 1)), provenance(DispatchPermission.INSTANCE_START));
    }

    private InstanceView close(InstanceView started, Instance.Resolution how,
                               InstanceService.Closing closing) {
        return instances.close(CONSOLE, started.address(), InstanceWire.SCOPE, how, closing,
            provenance(how == Instance.Resolution.CONCLUDED
                ? DispatchPermission.INSTANCE_CONCLUDE : DispatchPermission.INSTANCE_WITHDRAW));
    }

    private static Provenance provenance(DispatchPermission permission) {
        return new Provenance(InstanceWire.SUBJECT, null, permission, Provenance.Path.INTERFACE,
            null);
    }
}
