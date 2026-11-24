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
 * <p>The red state of each case is the read the lock replaces, observed while
 * the first is open: a read of the ledger without the lock finds no key, and a
 * second create deciding on it would create a second task under the key.
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

        Overlap overlap = stage().run(() -> create(selector, create), () -> create(selector,
            create));

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
    @Disabled("rebuilt in T5: an overlapping claim under one key answers the task with a new "
        + "receipt")
    void an_overlapping_claim_under_one_key_answers_the_task_with_a_new_receipt() {
        fail("rebuilt in T5");
    }

    @Test
    @Disabled("rebuilt in T11: an overlapping addendum under one key is attached once")
    void an_overlapping_addendum_under_one_key_is_attached_once() {
        fail("rebuilt in T11");
    }

    // -----------------------------------------------------------------------

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
