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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Two creates of one caller under one idempotency key, at once.
 *
 * <p>The first is held open in the test thread while the second runs on a
 * thread of its own, with the same key and the same arguments. The first
 * commits only once the database reports the second waiting on a lock the
 * first holds ({@link LockWaits}): the lock a create takes first, the
 * instance's for a create with a parent and the selector's for the short form.
 * The second decides on the key under that lock, after the first committed,
 * and answers what the first created, as a repeat after the commit does.
 *
 * <p>With the same key and other arguments -- another parent, or the short form
 * beside a create with a parent -- the two may take different locks. The
 * second then waits at the store's one key per caller and scope, is refused
 * with {@code IDEMPOTENCY_KEY_REUSED} once the first committed, and leaves no
 * row.
 *
 * <p>The red state of the four executable cases under one key --
 * {@link #an_overlapping_create_under_one_key_answers_the_first_task},
 * {@link #an_overlapping_short_form_under_one_key_starts_one_instance},
 * {@link #an_overlapping_create_under_one_key_with_another_parent_is_refused_as_reused}
 * and
 * {@link #an_overlapping_short_form_under_the_key_of_a_create_with_parent_is_refused_as_reused}
 * -- is the read the lock replaces, observed while the first is open: a read
 * of the ledger without the lock finds no key, and a second create deciding on
 * it would create a second task or write the key a second time. The case under
 * two keys, {@link #two_creates_under_two_keys_at_once_take_two_tasks},
 * observes its red state against the case under one: two keys are two calls,
 * and two tasks stand.
 *
 * <p>Two claims of one caller under one key, at once, take the lock of the
 * task's instance first, as every call on a task does: the second decides on
 * the key after the first committed, finds the caller holding the task by the
 * holding the first opened, and answers the task with a new receipt, as a
 * repeat after the commit does. Its red state is the read the lock replaces,
 * as above. The placeholder rebuilt in T11 observes nothing yet; it observes
 * its red state once its order builds it.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskKeyOverlapIT {

    private static final Actor CONSOLE = new Actor(InstanceWire.SUBJECT,
        DispatchPermission.TASK_COMMISSION, DispatchPermission.INSTANCE_START);
    private static final Provenance PROVENANCE = new Provenance(InstanceWire.SUBJECT, null,
        DispatchPermission.TASK_COMMISSION, Provenance.Path.INTERFACE, null);

    @Inject TaskService tasks;
    @Inject InstanceService instances;
    @Inject HoldingService holdings;
    @Inject TenantContext tenantContext;
    @Inject EntityManager em;

    @Test
    void an_overlapping_create_under_one_key_answers_the_first_task() throws Exception {
        String selector = TaskWire.plant("key-overlap", "review").selector();
        InstanceAddress instance = start(selector);
        String key = "overlap-" + InstanceWire.suffix();
        TaskService.Create create = new TaskService.Create(instance, drafting(), null, List.of(),
            IdempotencyKey.of(key));

        Overlap overlap = stage().run(() -> {
            TaskView first = create(selector, create);
            assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.idempotency_key "
                    + "WHERE idempotency_key = ?", key))
                .as("RED STATE, observed: while the first create is open, a read of the ledger "
                    + "without the lock finds no key, and a create deciding on it would create "
                    + "a second task")
                .isZero();
            return first;
        }, () -> create(selector, create));

        assertThat(overlap.waited()).as("the second waited on the lock of the instance").isTrue();
        assertThat(((TaskView) overlap.second()).address())
            .as("and answered the task the first created")
            .isEqualTo(((TaskView) overlap.first()).address());
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.task WHERE instance_id = ?",
                InstanceWire.instanceId(instance.complete())))
            .as("one task stands").isEqualTo(1);
    }

    @Test
    void an_overlapping_short_form_under_one_key_starts_one_instance() throws Exception {
        String selector = TaskWire.plant("key-short", "review").selector();
        String key = "short-" + InstanceWire.suffix();
        TaskService.Create create = new TaskService.Create(null, drafting(), null, List.of(),
            IdempotencyKey.of(key));

        Overlap overlap = stage().run(() -> {
            TaskView first = create(selector, create);
            assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.idempotency_key "
                    + "WHERE idempotency_key = ?", key))
                .as("RED STATE, observed: while the first short form is open, a read of the "
                    + "ledger without the lock finds no key, and a short form deciding on it "
                    + "would start a second instance")
                .isZero();
            return first;
        }, () -> create(selector, create));

        assertThat(overlap.waited()).as("the second waited on the lock of the selector").isTrue();
        assertThat(((TaskView) overlap.second()).address())
            .isEqualTo(((TaskView) overlap.first()).address());
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.instance i JOIN "
                + "dispatch.selector s ON s.id = i.selector_id WHERE s.name = ?", selector))
            .as("one instance was started").isEqualTo(1);
    }

    @Test
    void two_creates_under_two_keys_at_once_take_two_tasks() throws Exception {
        String selector = TaskWire.plant("two-keys", "review").selector();
        InstanceAddress instance = start(selector);

        Overlap overlap = stage().run(
            () -> create(selector, new TaskService.Create(instance, drafting(), null, List.of(),
                IdempotencyKey.of("one-" + InstanceWire.suffix()))),
            () -> create(selector, new TaskService.Create(instance, drafting(), null, List.of(),
                IdempotencyKey.of("two-" + InstanceWire.suffix()))));

        assertThat(List.of(((TaskView) overlap.first()).address().sub(),
                ((TaskView) overlap.second()).address().sub()))
            .as("RED STATE, observed against the case above: two keys are two calls")
            .containsExactly(1, 2);
    }

    @Test
    void an_overlapping_create_under_one_key_with_another_parent_is_refused_as_reused()
            throws Exception {
        String selector = TaskWire.plant("key-parents", "review").selector();
        InstanceAddress one = start(selector);
        InstanceAddress other = start(selector);
        String key = "parents-" + InstanceWire.suffix();

        Overlap overlap = stage().run(() -> {
            TaskView first = create(selector, new TaskService.Create(one, drafting(), null,
                List.of(), IdempotencyKey.of(key)));
            assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.idempotency_key "
                    + "WHERE idempotency_key = ?", key))
                .as("RED STATE, observed: while the first create is open, the second, under "
                    + "the lock of another instance, finds the key unspent and goes on to "
                    + "write it a second time")
                .isZero();
            return first;
        }, () -> create(selector, new TaskService.Create(other, drafting(), null, List.of(),
            IdempotencyKey.of(key))));

        assertReused(overlap, key, other);
    }

    @Test
    void an_overlapping_short_form_under_the_key_of_a_create_with_parent_is_refused_as_reused()
            throws Exception {
        String selector = TaskWire.plant("key-forms", "review").selector();
        InstanceAddress parent = start(selector);
        String key = "forms-" + InstanceWire.suffix();

        Overlap overlap = stage().run(() -> {
            TaskView first = create(selector, new TaskService.Create(parent, drafting(), null,
                List.of(), IdempotencyKey.of(key)));
            assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.idempotency_key "
                    + "WHERE idempotency_key = ?", key))
                .as("RED STATE, observed: while the create with a parent is open, the short "
                    + "form finds the key unspent and goes on to write it a second time")
                .isZero();
            return first;
        }, () -> create(selector, new TaskService.Create(null, drafting(), null, List.of(),
            IdempotencyKey.of(key))));

        assertReused(overlap, key, null);
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.instance i JOIN "
                + "dispatch.selector s ON s.id = i.selector_id WHERE s.name = ?", selector))
            .as("the short form refused started no instance").isEqualTo(1);
    }

    @Test
    void an_overlapping_claim_under_one_key_answers_the_task_with_a_new_receipt()
            throws Exception {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "claim-overlap");
        String key = "claim-" + InstanceWire.suffix();
        HoldingService.Claim claim = new HoldingService.Claim(null, IdempotencyKey.of(key));

        Overlap overlap = stage().run(() -> {
            TaskView first = holdings.claim(HoldingFixture.EXECUTOR, task, InstanceWire.SCOPE,
                claim, HoldingFixture.executing(HoldingFixture.EXECUTOR));
            assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.idempotency_key "
                    + "WHERE idempotency_key = ?", key))
                .as("RED STATE, observed: while the first claim is open, a read of the ledger "
                    + "without the lock finds no key, and a claim deciding on it would take the "
                    + "task a second time")
                .isZero();
            return first;
        }, () -> holdings.claim(HoldingFixture.EXECUTOR, task, InstanceWire.SCOPE, claim,
            HoldingFixture.executing(HoldingFixture.EXECUTOR)));

        assertThat(overlap.waited()).as("the second waited on the first's lock").isTrue();
        assertThat(overlap.second()).as("and answered the task the first took up")
            .isInstanceOfSatisfying(TaskView.class, second -> {
                TaskView first = (TaskView) overlap.first();
                assertThat(second.address()).isEqualTo(first.address());
                assertThat(second.standing().receipt()).as("with a new receipt")
                    .isNotEqualTo(first.standing().receipt());
            });
        assertThat(List.of(HoldingFixture.rows("holding", task),
                HoldingFixture.rows("receipt", task), HoldingFixture.rows("lease", task)))
            .as("one holding and one lease, with two receipts").containsExactly(1L, 2L, 1L);
    }

    @Test
    @Disabled("rebuilt in T11: an overlapping addendum under one key is attached once")
    void an_overlapping_addendum_under_one_key_is_attached_once() {
        fail("rebuilt in T11");
    }

    // -----------------------------------------------------------------------

    /**
     * The first created its task and spent the key; the second waited on it,
     * was refused as a reuse of the key, and left no row: no task in the
     * instance it named, where it named one, and the one key the first wrote.
     */
    private static void assertReused(Overlap overlap, String key,
                                     @Nullable InstanceAddress second) {
        assertThat(overlap.waited()).as("the second waited on the first").isTrue();
        assertThat(overlap.first()).as("the first created its task").isInstanceOf(TaskView.class);
        assertThat(overlap.second())
            .as("the second is refused as a reuse of the key, not failed")
            .isInstanceOfSatisfying(DispatchException.class, refused -> assertThat(
                refused.reason()).isEqualTo(DispatchException.Reason.IDEMPOTENCY_KEY_REUSED));
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.idempotency_key "
                + "WHERE idempotency_key = ?", key))
            .as("one key stands, the first's").isEqualTo(1);
        if (second != null) {
            assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.task "
                    + "WHERE instance_id = ?", InstanceWire.instanceId(second.complete())))
                .as("the instance the second named holds no task").isZero();
        }
    }

    private Overlap.Stage stage() {
        return new Overlap.Stage(em, tenantContext, InstanceWire.TENANT);
    }

    private static TaskService.Drafting drafting() {
        return new TaskService.Drafting("a title", null, null, null, List.of(), null, null);
    }

    private InstanceAddress start(String selector) {
        return instances.start(CONSOLE, InstanceWire.SLUG, InstanceWire.SCOPE, selector,
            new InstanceService.Start("an instance", null, null, List.of(), List.of(), Map.of()),
            new Provenance(InstanceWire.SUBJECT, null, DispatchPermission.INSTANCE_START,
                Provenance.Path.INTERFACE, null)).address();
    }

    private TaskView create(String selector, TaskService.Create create) {
        return tasks.create(CONSOLE, InstanceWire.SLUG, InstanceWire.SCOPE, selector, create,
            PROVENANCE);
    }
}
