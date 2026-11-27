/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import ai.kumbuka.dispatch.domain.Draft;
import ai.kumbuka.dispatch.domain.TaskService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probe 3 — the lock closes, and the data is still there.
 *
 * <p>Both halves belong to the probe. A read with no tenant bound returns
 * nothing, which is the guarantee; and the same read with the tenant bound
 * returns the rows, which is what distinguishes a lock from an empty table.
 * Asserting only the first half would pass against a database where the
 * insert never landed, the schema is wrong, or the rows were deleted — every
 * one of which looks exactly like perfect isolation from the outside.
 *
 * <p>The second pair of tests takes the two enforcement layers apart. Both
 * are on at once in normal operation, so an ordinary assertion cannot say
 * which one did the work — and a layer that is quietly doing nothing is a
 * layer that will not be there when the other one is removed. Each is
 * therefore observed alone, by switching the other off for the length of one
 * assertion. The tasks are written through the kernel, so both layers are on
 * the path that wrote them, and read in a scope of this test's own.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class FailClosedProbeIT {

    /**
     * A fresh pair of tenants and a fresh scope per test method — the tests
     * share one database, and a count under a fixed tenant would include
     * whatever an earlier test planted there. See the same note in {@link
     * RowLevelSecurityProbeIT}.
     */
    private UUID tenantA;
    private UUID tenantB;
    private UUID scope;

    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;
    @Inject EntityManager em;

    @BeforeEach
    void freshTenants() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        scope = UUID.randomUUID();
    }

    @Test
    void an_unbound_read_returns_nothing_and_the_rows_are_still_there() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            Db.insertTask(c, tenantA, "fail-closed-a");
            c.commit();

            // First half: no binding, no rows. The predicate compares against
            // NULL, which a policy treats as failing, so the table is closed
            // rather than open.
            Db.bindTenant(c, null);
            assertThat(Db.countTasks(c))
                .as("a transaction that never bound a tenant must see nothing at all — "
                    + "the predicate fails closed, and this is the half that is the guarantee")
                .isZero();

            // Second half: bind, and the row is there. Without this the
            // assertion above would hold just as well against a table that is
            // simply empty, and an empty table proves nothing about a policy.
            Db.bindTenant(c, tenantA);
            assertThat(Db.countTasks(c))
                .as("RED STATE, observed: with the tenant bound the row is present and "
                    + "unchanged — which is what makes the emptiness above a lock rather than "
                    + "an absence")
                .isEqualTo(1);
        }
    }

    /**
     * Layer 1 alone: the ORM filter, with the database policy switched off.
     *
     * <p>Hibernate rewrites every statement it routes with the tenant
     * predicate. That holds without any help from the database, and this is
     * where it is seen holding: the policy is disabled for the length of the
     * assertion, so nothing but the filter is left to do the work.
     */
    @Test
    void the_orm_filter_isolates_on_its_own_when_the_policy_is_off() throws Exception {
        plantOneTaskPerTenant();

        try (Connection c = Db.asService()) {
            try {
                // Switched by the table's OWNER — the migrator. The service
                // role owns nothing and may not reconfigure a policy; the
                // measurement below is still its own, which is the half that
                // matters.
                Db.switchPolicyAsOwner(c, "ALTER TABLE dispatch.draft DISABLE ROW LEVEL SECURITY");

                try (AutoCloseable ignored = tenantContext.bind(tenantA)) {
                    assertThat(draftTitles())
                        .as("with the policy disabled, the ORM filter is the only thing "
                            + "scoping this read — and it must still scope it")
                        .containsExactly("layers-a");
                }
                // The other side of the same observation: raw SQL, which the
                // ORM never rewrote, now sees both. That is precisely the gap
                // layer 2 exists to close, and it is visible here.
                Db.bindTenant(c, tenantA);
                assertThat(draftsInScope(c))
                    .as("RED STATE, observed: with the policy off, raw SQL under tenant A "
                        + "reads tenant B's draft too. The ORM filter cannot reach a statement "
                        + "it did not build, which is the whole reason for a second layer")
                    .isEqualTo(2);
                c.rollback();
            } finally {
                Db.switchPolicyAsOwner(c, "ALTER TABLE dispatch.draft ENABLE ROW LEVEL SECURITY");
            }
        }
    }

    /**
     * Layer 2 alone: the database policy, against a statement the ORM never
     * saw. No switching is needed here — raw SQL is by definition outside
     * layer 1, so anything that scopes it is the policy.
     */
    @Test
    void the_policy_isolates_raw_sql_that_the_orm_never_touched() throws Exception {
        plantOneTaskPerTenant();

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            assertThat(draftsInScope(c))
                .as("raw SQL bypasses the ORM filter entirely, so this count is the policy's "
                    + "work and nobody else's")
                .isEqualTo(1);
            Db.bindTenant(c, tenantB);
            assertThat(draftsInScope(c)).as("and B sees its own one").isEqualTo(1);
        }
    }

    /** One task in draft per tenant, written through the kernel so both layers are on the path. */
    private void plantOneTaskPerTenant() throws Exception {
        for (UUID tenant : List.of(tenantA, tenantB)) {
            TenantTasks.plant(tenant, scope);
            try (AutoCloseable ignored = tenantContext.bind(tenant)) {
                tasks.create(TenantTasks.ACTOR, "probe", scope, TenantTasks.SELECTOR,
                    TenantTasks.create(tenant.equals(tenantA) ? "layers-a" : "layers-b", null),
                    TenantTasks.PROVENANCE);
            }
        }
    }

    /** The titles of the drafts of this test's scope the ORM reads under the bound tenant. */
    private List<String> draftTitles() {
        return QuarkusTransaction.requiringNew().call(() -> em.createQuery(
                "SELECT d FROM Draft d WHERE d.scopeId = :scope", Draft.class)
            .setParameter("scope", scope)
            .getResultList()
            .stream()
            .map(Draft::title)
            .toList());
    }

    /** The drafts of this test's scope the session sees, past the ORM. */
    private long draftsInScope(Connection c) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
                "SELECT count(*) FROM dispatch.draft WHERE scope_id = ?")) {
            st.setObject(1, scope);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
