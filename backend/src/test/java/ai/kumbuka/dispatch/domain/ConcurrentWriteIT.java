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
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two writers present the same conflict token of a draft: exactly one writes,
 * and the other is refused with {@code CONFLICT_TOKEN_STALE} and the task as
 * it now stands (C10.2, DEC-0041).
 *
 * <p>The comparison of the presented token and the write happen under the
 * locks of the task's row and its draft's row, refreshed under them (C11.1).
 * The first case makes the two writers overlap for real: the first holds the
 * locks in an open transaction while the second is started, and the database
 * is asked whether the second waits on the first ({@link LockWaits}) before
 * the first commits. The red state is the read the lock replaces: while the
 * first is open, a read without the lock still finds the draft as it was, and
 * a writer deciding on it would find its token current and write too.
 *
 * <p>The second case is the wire: the refusal carries {@code address}, {@code
 * conflict_token}, {@code fields} and {@code service} with status 412, the
 * token being the one the winner's answer handed out.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ConcurrentWriteIT {

    private static final Actor CONSOLE = new Actor(InstanceWire.SUBJECT,
        DispatchPermission.TASK_COMMISSION, DispatchPermission.INSTANCE_START);
    private static final Provenance PROVENANCE = new Provenance(InstanceWire.SUBJECT, null,
        DispatchPermission.TASK_COMMISSION, Provenance.Path.INTERFACE, null);

    @Inject TaskService tasks;
    @Inject InstanceService instances;
    @Inject TenantContext tenantContext;
    @Inject EntityManager em;
    @Inject TestIdentityAssociation identity;

    @Test
    void two_writers_with_one_token_exactly_one_writes() throws Exception {
        TaskView draft = create("writers");

        Overlap overlap = new Overlap.Stage(em, tenantContext, InstanceWire.TENANT).run(() -> {
            TaskView first = update(draft, "the first writer");
            assertThat(InstanceWire.rows("SELECT title FROM dispatch.draft WHERE task_id = ?",
                    draft.id()))
                .as("RED STATE, observed: while the first writer is open, a read without the "
                    + "lock still finds the draft as it was, under the token both presented")
                .containsExactly(Map.of("title", "a title"));
            return first;
        }, () -> update(draft, "the second writer"));

        assertThat(overlap.waited()).as("the second writer waited on the first's lock").isTrue();
        assertThat(overlap.second())
            .as("the second found the token moved on and was refused")
            .isInstanceOfSatisfying(DispatchException.class, refused -> {
                assertThat(refused.reason())
                    .isEqualTo(DispatchException.Reason.CONFLICT_TOKEN_STALE);
                assertThat(refused.task()).hasValueSatisfying(at -> assertThat(
                    at.conflictToken()).isEqualTo(((TaskView) overlap.first()).conflictToken()));
            });
        assertThat(InstanceWire.rows("SELECT title FROM dispatch.draft WHERE task_id = ?",
                draft.id()))
            .as("the first writer's title stands, and the second wrote nothing")
            .containsExactly(Map.of("title", "the first writer"));
    }

    @Test
    void the_loser_is_told_the_task_as_it_now_stands_412() {
        PlatformFixture.grantDirectoryAccess();
        InstanceWire.as(identity, InstanceWire.CONSOLE);
        TaskWire.Planted planted = TaskWire.plant("loser", "review");
        Map<String, Object> created = TaskWire.drafted(planted.selector(),
            TaskWire.started(planted.selector()), "a title");
        String address = String.valueOf(created.get(InstanceWire.ADDRESS));
        String token = String.valueOf(created.get(InstanceWire.TOKEN));
        Response winner = TaskWire.rest("PATCH", address, null,
            Map.of(InstanceWire.FIELDS, Map.of("title", "the winner")), token);

        Response loser = TaskWire.rest("PATCH", address, null,
            Map.of(InstanceWire.FIELDS, Map.of("title", "the loser")), token);

        assertThat(winner.statusCode()).as(winner.asString()).isEqualTo(200);
        assertThat(loser.statusCode()).as(loser.asString()).isEqualTo(412);
        assertThat(loser.jsonPath().getString("reason")).isEqualTo("CONFLICT_TOKEN_STALE");
        assertThat(List.of(loser.jsonPath().getString("data.address"),
                loser.jsonPath().getString("data.conflict_token"),
                loser.jsonPath().getString("data.service")))
            .as("the task as it stands, under the token the winner's answer handed out")
            .containsExactly(address, winner.jsonPath().getString("conflict_token"), "dispatch");
        assertThat(loser.jsonPath().getMap("data.fields")).containsEntry("state", "draft")
            .containsEntry("sub", 1);
        assertThat(loser.asString()).as("and nothing of what the loser sent")
            .doesNotContain("the loser");
    }

    // -----------------------------------------------------------------------

    private TaskView create(String prefix) {
        String selector = TaskWire.plant(prefix, "review").selector();
        InstanceAddress instance = instances.start(CONSOLE, InstanceWire.SLUG, InstanceWire.SCOPE,
            selector, new InstanceService.Start("an instance", null, null, List.of(), List.of(),
                Map.of()), new Provenance(InstanceWire.SUBJECT, null,
                DispatchPermission.INSTANCE_START, Provenance.Path.INTERFACE, null)).address();
        return tasks.create(CONSOLE, InstanceWire.SLUG, InstanceWire.SCOPE, selector,
            new TaskService.Create(instance, new TaskService.Drafting("a title", null, null, null,
                List.of(), null, null), null, List.of(), IdempotencyKey.NONE), PROVENANCE);
    }

    private TaskView update(TaskView standing, String title) {
        return tasks.update(CONSOLE, standing.address(), InstanceWire.SCOPE,
            new TaskService.Update(standing.conflictToken(), title, null, null, null, null, null,
                null, Set.of()));
    }
}
