/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.TaskRepository;
import ai.kumbuka.dispatch.surface.InstanceWire;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static ai.kumbuka.dispatch.domain.HoldingFixture.EXECUTOR;
import static ai.kumbuka.dispatch.domain.HoldingFixture.OTHER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A claim decides on the task's row as it stands under the lock (C11.1).
 *
 * <p>Two claims of one task at once: the first is held open in the test
 * thread while the second runs on a thread of its own, and the first commits
 * only once the database reports the second waiting on a lock the first holds
 * ({@link LockWaits}). Exactly one takes the task; the second decides after
 * the first committed and is refused as held. The red state is the read the
 * lock replaces, observed while the first is open: a read of the store without
 * the lock finds no holding, and a claim deciding on it would take the task a
 * second time.
 *
 * <p>A claim after an unlocked read: the first transaction reads the task
 * without a lock, which puts it into its persistence context; a second
 * transaction claims it and commits; only then does the first claim. It
 * decides on the row as it stands under the lock and is refused as held.
 * Nothing waits on a lock there: the order is fixed by the test, not by a
 * timing. Its red state is the row the unlocked read left in the context,
 * still open, observed before the claim.
 *
 * <p>The old case of a bracket root closing after an unlocked read of its
 * child has no subject in the new model, which has no root; the rule it held
 * -- a closing keeps a task another transaction closed as it was -- is held by
 * {@code TaskBracketLockIT.a_task_closed_by_a_second_transaction_before_the_closing_keeps_its_state}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskLockedRowIT {

    @Inject HoldingService holdings;
    @Inject TaskService tasks;
    @Inject InstanceService instances;
    @Inject TaskRepository taskRows;
    @Inject TenantContext tenantContext;
    @Inject EntityManager em;

    @Test
    void two_claims_at_once_take_the_task_once_and_the_second_is_refused_as_held()
            throws Exception {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "claim-race");

        Overlap overlap = new Overlap.Stage(em, tenantContext, InstanceWire.TENANT).run(() -> {
            TaskView first = HoldingFixture.claim(holdings, EXECUTOR, task);
            assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.holding "
                    + "WHERE task_id = ?", HoldingFixture.taskId(task)))
                .as("RED STATE, observed: while the first claim is open, a read of the store "
                    + "without the lock finds no holding, and a claim deciding on it would take "
                    + "the task a second time")
                .isZero();
            return first;
        }, () -> HoldingFixture.claim(holdings, OTHER, task));

        assertThat(overlap.waited()).as("the second waited on the first's lock").isTrue();
        assertThat(overlap.first()).as("the first took the task").isInstanceOf(TaskView.class);
        assertThat(overlap.second())
            .as("the second is refused as held, not failed")
            .isInstanceOfSatisfying(DispatchException.class, refused -> assertThat(
                refused.reason()).isEqualTo(DispatchException.Reason.CLAIM_HELD));
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.holding WHERE task_id = ?",
                HoldingFixture.taskId(task)))
            .as("one holding stands").isEqualTo(1);
    }

    @Test
    void a_claim_after_an_unlocked_read_decides_on_the_claim_another_transaction_committed()
            throws Exception {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "claim-unlocked");
        long instance = InstanceWire.instanceId(task.instance().complete());
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            QuarkusTransaction.begin();
            try {
                Task read = taskRows.task(InstanceWire.SCOPE, instance, task.sub()).orElseThrow();
                Future<TaskView> other = thread.submit(() -> {
                    var request = io.quarkus.arc.Arc.container().requestContext();
                    request.activate();
                    try (AutoCloseable ignored = tenantContext.bind(InstanceWire.TENANT)) {
                        return HoldingFixture.claim(holdings, OTHER, task);
                    } finally {
                        request.terminate();
                    }
                });
                assertThat(other.get(30, TimeUnit.SECONDS).state())
                    .as("another transaction claimed the task and committed").isEqualTo("active");
                assertThat(read.state())
                    .as("RED STATE, observed: the context still holds the task as the unlocked "
                        + "read found it, open, so a claim deciding on it would take it again")
                    .isEqualTo("open");

                Throwable refused = null;
                try {
                    HoldingFixture.claim(holdings, EXECUTOR, task);
                } catch (DispatchException e) {
                    refused = e;
                }
                assertThat(refused).as("the claim decides on the row under the lock: held")
                    .isInstanceOfSatisfying(DispatchException.class, e -> assertThat(e.reason())
                        .isEqualTo(DispatchException.Reason.CLAIM_HELD));
            } finally {
                QuarkusTransaction.rollback();
            }
        } finally {
            thread.shutdownNow();
        }
        assertThat(HoldingFixture.holdingRows(task)).as("the other's holding alone")
            .hasSize(1);
        assertThat(List.of(HoldingFixture.taskRow(task).get("state"))).containsExactly("active");
    }
}
