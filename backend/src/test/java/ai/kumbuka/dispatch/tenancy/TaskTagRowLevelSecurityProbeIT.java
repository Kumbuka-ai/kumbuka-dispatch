/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenant isolation of {@code task_tag} (V24), with both lines it rests on
 * removed in the run and watched to break: FORCE ROW LEVEL SECURITY, and the
 * policy itself.
 *
 * <p>The tenant test is the first method: under a bound tenant a session sees
 * the tenant's own relation and only that, in both directions. The two red
 * halves follow {@link RowLevelSecurityProbeIT}: each removes one line against
 * the running database, observes the tenant test's statement fail, and puts
 * the line back in a {@code finally}, so the red state is observed on every
 * build and the green state after it shows the removal was the cause.
 *
 * <p>Raw JDBC on purpose: the ORM's tenant filter would hide what the policy
 * does on its own.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskTagRowLevelSecurityProbeIT {

    private static final String POLICY = "task_tag_tenant_isolation";

    private static final String CREATE_POLICY = "CREATE POLICY " + POLICY + " ON dispatch.task_tag"
        + " USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)"
        + " WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)";

    private UUID tenantA;
    private UUID tenantB;
    private UUID tagA;
    private UUID tagB;
    private long taskB;

    /** Fresh tenants, tasks and tags per method, so no count depends on another test. */
    @BeforeEach
    void plant() throws SQLException {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        tagA = UUID.randomUUID();
        tagB = UUID.randomUUID();
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            long taskA = Db.insertTask(c, tenantA, "tag-probe-a");
            attach(c, tenantA, taskA, tagA);
            Db.bindTenant(c, tenantB);
            taskB = Db.insertTask(c, tenantB, "tag-probe-b");
            attach(c, tenantB, taskB, tagB);
            c.commit();
        }
    }

    /** The tenant test: a bound tenant sees its own relation and only that. */
    @Test
    void a_bound_tenant_sees_its_own_tags_and_only_those() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            assertThat(visible(c, tagA)).as("A sees A's relation").isEqualTo(1);
            assertThat(visible(c, tagB)).as("and not B's").isZero();
            Db.bindTenant(c, tenantB);
            assertThat(visible(c, tagB)).as("B sees B's relation").isEqualTo(1);
            assertThat(visible(c, tagA)).as("and not A's").isZero();
            Db.bindTenant(c, null);
            assertThat(visible(c, tagA) + visible(c, tagB))
                .as("and a session bound to no tenant sees neither").isZero();
        }
    }

    /**
     * Red half one: without FORCE the owner -- the migrator -- reads the other
     * tenant's relation under A's binding. With FORCE it is bound by the policy
     * like every other role.
     */
    @Test
    void without_force_the_owner_reads_the_other_tenants_tags() throws SQLException {
        try (Connection c = Db.asMigrator()) {
            Db.bindTenant(c, tenantA);
            assertThat(visible(c, tagB))
                .as("green state: with FORCE, the owner under A sees nothing of B").isZero();
            c.commit();
            try {
                Db.switchPolicyAsOwner(c,
                    "ALTER TABLE dispatch.task_tag NO FORCE ROW LEVEL SECURITY");
                Db.bindTenant(c, tenantA);
                assertThat(visible(c, tagB))
                    .as("RED STATE, observed: with FORCE removed from task_tag, the owner bound "
                        + "to tenant A reads tenant B's relation; the tenant test's 'and not "
                        + "B's' fails for the owner")
                    .isEqualTo(1);
                c.commit();
            } finally {
                Db.switchPolicyAsOwner(c, "ALTER TABLE dispatch.task_tag FORCE ROW LEVEL SECURITY");
            }
            Db.bindTenant(c, tenantA);
            assertThat(visible(c, tagB))
                .as("and restored: the isolation is back, so the red state was the removal")
                .isZero();
        }
        a_bound_tenant_sees_its_own_tags_and_only_those();
    }

    /**
     * Red half two: without the policy the table answers no tenant the way the
     * tenant test says it must. Row-level security stays enabled and forced,
     * so with no policy left it admits no row at all: the service role bound to
     * A no longer sees A's own relation, and the tenant test fails on its first
     * statement.
     */
    @Test
    void without_the_policy_the_tenant_test_fails() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            assertThat(visible(c, tagA)).as("green state: A sees its own relation")
                .isEqualTo(1);
            try {
                Db.switchPolicyAsOwner(c, "DROP POLICY " + POLICY + " ON dispatch.task_tag");
                Db.bindTenant(c, tenantA);
                assertThat(visible(c, tagA))
                    .as("RED STATE, observed: with the policy of task_tag removed, A no longer "
                        + "sees its own relation; the tenant test's 'A sees A's relation' fails")
                    .isZero();
            } finally {
                Db.switchPolicyAsOwner(c, CREATE_POLICY);
            }
            Db.bindTenant(c, tenantA);
            assertThat(visible(c, tagA)).as("and restored: A sees its own relation again")
                .isEqualTo(1);
            assertThat(visible(c, tagB)).as("and not B's").isZero();
        }
        a_bound_tenant_sees_its_own_tags_and_only_those();
    }

    /** The write half: a session bound to A cannot attach a tag to B's task. */
    @Test
    void a_relation_under_a_foreign_tenant_is_refused_by_the_policy() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            try {
                attach(c, tenantB, taskB, UUID.randomUUID());
                throw new AssertionError("a session bound to tenant A wrote a relation of "
                    + "tenant B: WITH CHECK is missing from the policy of task_tag");
            } catch (SQLException expected) {
                assertThat(expected.getMessage())
                    .as("the refusal comes from the policy").contains("row-level security");
            } finally {
                c.rollback();
            }
        }
    }

    private static void attach(Connection c, UUID tenant, long task, UUID tag)
            throws SQLException {
        try (var st = c.prepareStatement("INSERT INTO dispatch.task_tag "
                + "(tenant_id, task_id, tag_id, attached_by) VALUES (?::uuid, ?, ?::uuid, 'probe')")) {
            st.setString(1, tenant.toString());
            st.setLong(2, task);
            st.setString(3, tag.toString());
            st.executeUpdate();
        }
    }

    /** The relations of one tag as the current session sees them. */
    private static long visible(Connection c, UUID tag) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT count(*) FROM dispatch.task_tag WHERE tag_id = ?::uuid")) {
            st.setString(1, tag.toString());
            try (var rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
