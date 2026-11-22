/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The writer of acts against the store line, under the service role and its
 * grants: what it writes, how two writers on one instance share its sequence,
 * and what the store refuses of what it is handed.
 *
 * <p>Every expectation is written here, from the order that specified the
 * writer and from the store line's table {@code dispatch.act}: the columns an
 * act carries, the numbering from 1 without a gap, and the check that admits
 * an authentication level only on the path {@code console}. The rows are read
 * back by SQL as the store holds them, never through the mapping under test.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ActServiceIT {

    /** The tenant of the test configuration, which an unbound thread resolves to. */
    private static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);

    @Inject ActService acts;
    @Inject TenantContext tenantContext;
    @Inject EntityManager em;

    @Test
    void a_start_act_is_written_to_a_planted_instance_with_every_field_of_its_provenance() {
        UUID scope = UUID.randomUUID();
        InstanceFixture.Planted planted = InstanceFixture.plant(TENANT, scope, "sprint");
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        Act start = acts.write(scope, planted.instance(), Act.Kind.START, Act.State.RUNNING, null,
            new Provenance("delegate-of-ada", "ada", DispatchPermission.INSTANCE_START,
                Provenance.Path.INTERFACE, null));
        Act conclude = acts.write(scope, planted.instance(), Act.Kind.CONCLUDE, Act.State.CLOSED,
            null, new Provenance("ada", null, DispatchPermission.INSTANCE_CONCLUDE,
                Provenance.Path.CONSOLE, "step-up"));
        Instant after = Instant.now();

        Map<String, Object> first = InstanceFixture.actRow(start.id());
        assertThat(first)
            .as("the start act stands in the store with the instance, the first number of its "
                + "sequence, no task, its kind and target state, the delegated identity and the "
                + "person it belongs to, the permission, the path interface and no "
                + "authentication level")
            .containsEntry("tenant_id", TENANT)
            .containsEntry("scope_id", scope)
            .containsEntry("instance_id", planted.instance())
            .containsEntry("sequence", 1)
            .containsEntry("task_id", null)
            .containsEntry("kind", "start")
            .containsEntry("to_state", "running")
            .containsEntry("actor_subject", "delegate-of-ada")
            .containsEntry("actor_owner_subject", "ada")
            .containsEntry("permission", "dispatch.instance.start")
            .containsEntry("path", "interface")
            .containsEntry("authentication_level", null);
        assertThat(((Timestamp) first.get("at")).toInstant())
            .as("the time of the act is the service's, taken while it was written")
            .isBetween(before, after);

        assertThat(InstanceFixture.actRow(conclude.id()))
            .as("the act of the person in the console carries no owner, the path console and "
                + "the authentication level of the session, and takes the next number")
            .containsEntry("sequence", 2)
            .containsEntry("kind", "conclude")
            .containsEntry("to_state", "closed")
            .containsEntry("actor_subject", "ada")
            .containsEntry("actor_owner_subject", null)
            .containsEntry("permission", "dispatch.instance.conclude")
            .containsEntry("path", "console")
            .containsEntry("authentication_level", "step-up");

        assertThat(List.of(start.sequence(), start.kind(), start.toState(), start.actorSubject(),
                start.actorOwnerSubject().orElseThrow(), start.permission().orElseThrow(),
                start.path().orElseThrow(), start.authenticationLevel().isPresent(),
                start.taskId().isPresent(), start.instanceId(), start.scopeId(), start.tenantId()))
            .as("and the act answered is the act written, the tenant the bound one")
            .containsExactly(1, "start", "running", "delegate-of-ada", "ada",
                "dispatch.instance.start", "interface", false, false, planted.instance(), scope,
                TENANT);
    }

    @Test
    void two_writers_at_once_on_one_instance_take_different_consecutive_numbers()
            throws Exception {
        UUID scope = UUID.randomUUID();
        InstanceFixture.Planted planted = InstanceFixture.plant(TENANT, scope, "sprint");
        Provenance provenance = new Provenance("delegate-of-ada", "ada",
            DispatchPermission.INSTANCE_START, Provenance.Path.INTERFACE, null);

        Overlap overlap = new Overlap.Stage(em, tenantContext, TENANT).run(
            () -> acts.write(scope, planted.instance(), Act.Kind.START, Act.State.RUNNING, null,
                provenance),
            () -> acts.write(scope, planted.instance(), Act.Kind.REFER, Act.State.RUNNING, null,
                provenance));

        assertThat(overlap.waited())
            .as("the second writer waited on the lock the first held on the instance's row, so "
                + "the two writes overlapped and were not merely one after the other")
            .isTrue();
        assertThat(overlap.second())
            .as("the second writer wrote, and was not refused for a number already taken")
            .isInstanceOf(Act.class);
        assertThat(List.of(((Act) overlap.first()).sequence(), ((Act) overlap.second()).sequence()))
            .as("the two writers took different numbers, one after the other, without a gap")
            .containsExactly(1, 2);
        assertThat(InstanceFixture.actsOf(planted.instance()))
            .as("and both acts stand in the store").isEqualTo(2);
    }

    @Test
    void the_store_refuses_an_act_on_the_path_interface_with_an_authentication_level()
            throws SQLException {
        UUID scope = UUID.randomUUID();
        InstanceFixture.Planted planted = InstanceFixture.plant(TENANT, scope, "sprint");
        Provenance interfaceWithLevel = new Provenance("delegate-of-ada", "ada",
            DispatchPermission.INSTANCE_START, Provenance.Path.INTERFACE, "step-up");

        long instance = planted.instance();
        assertThatThrownBy(() -> acts.write(scope, instance, Act.Kind.START,
                Act.State.RUNNING, null, interfaceWithLevel))
            .as("an authentication level is admitted on the path console alone, and the store "
                + "refuses the act by its check")
            .hasStackTraceContaining("ck_act_authentication_level");
        assertThat(InstanceFixture.actsOf(planted.instance()))
            .as("and nothing was written").isZero();

        String insert = "INSERT INTO dispatch.act (tenant_id, scope_id, instance_id, sequence, "
            + "kind, actor_subject, actor_owner_subject, permission, path, authentication_level, "
            + "at, to_state) VALUES (?, ?, ?, 1, 'start', 'delegate-of-ada', 'ada', "
            + "'dispatch.instance.start', 'interface', 'step-up', now(), 'running')";
        try (Connection c = InstanceFixture.admin()) {
            c.setAutoCommit(false);
            Savepoint removed = c.setSavepoint();
            try (Statement s = c.createStatement()) {
                s.execute("ALTER TABLE dispatch.act DROP CONSTRAINT ck_act_authentication_level");
            }
            int written;
            try (PreparedStatement st = c.prepareStatement(insert)) {
                st.setObject(1, TENANT);
                st.setObject(2, scope);
                st.setLong(3, planted.instance());
                written = st.executeUpdate();
            }
            c.rollback(removed);
            c.rollback();
            assertThat(written)
                .as("RED STATE, observed: with the check of the path taken off act, the same act "
                    + "is taken, so the refusal above is the check and not the row")
                .isEqualTo(1);
        }
        assertThat(InstanceFixture.actsOf(planted.instance()))
            .as("and restored: the act taken without the check was rolled back with it")
            .isZero();
    }
}
