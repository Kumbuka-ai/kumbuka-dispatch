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
 * Probe 1 — row-level security, with the red state observed rather than
 * described.
 *
 * <p>Each test below asserts the guarantee AND then removes the line the
 * guarantee rests on and watches it break, in the same run, on the same
 * connection, before putting the line back. That is the difference between a
 * gate and a comment. A test that only asserts the green half passes
 * identically on a schema where the policy was silently dropped — which is
 * the failure mode this whole arrangement exists to catch, so a probe blind
 * to it is worth very little.
 *
 * <p>The removals are made against the running database and undone in a
 * {@code finally}, rather than as a throwaway migration. The effect is the
 * same and the observation is stronger: a throwaway migration is run once by
 * whoever wrote it, and this runs on every build. What was observed on one
 * afternoon is a memory; what is observed on every build is a gate.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class RowLevelSecurityProbeIT {

    /**
     * A fresh pair of tenants per test method.
     *
     * <p>The tests share one database, and row-level security counts every row
     * a tenant owns — including rows an earlier test planted. Fixed tenant ids
     * would make each assertion depend on which tests ran before it, so the
     * suite would pass or fail by execution order and the failure would look
     * like a broken policy. Fresh ids make each test's arithmetic its own.
     */
    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void freshTenants() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
    }

    /**
     * The policy filters a read to the bound tenant, and rows belonging to
     * another tenant are not merely hidden from a listing — they are absent
     * from a count, which is the form that cannot be papered over by a
     * presentation layer.
     */
    @Test
    void a_read_under_one_tenant_does_not_see_another_tenants_row() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            Db.insertScope(c, tenantA, "probe-a");
            Db.bindTenant(c, tenantB);
            Db.insertScope(c, tenantB, "probe-b");
            c.commit();

            Db.bindTenant(c, tenantA);
            assertThat(Db.countScopes(c))
                .as("a session bound to tenant A must see A's row and only A's")
                .isEqualTo(1);

            Db.bindTenant(c, tenantB);
            assertThat(Db.countScopes(c))
                .as("and symmetrically for B — otherwise the filter is not a filter but "
                    + "a coincidence about which rows happen to exist")
                .isEqualTo(1);
        }
    }

    /**
     * The red half of probe 1, and the reason FORCE is in V3.
     *
     * <p>{@code ENABLE ROW LEVEL SECURITY} switches a policy on for every role
     * EXCEPT the table's owner. This service connects as the owner of its own
     * tables, so under ENABLE alone the policy would be switched off for the
     * only role that ever connects — present in the catalog, visible in the
     * migration, and inert. {@code FORCE} is what binds the owner.
     *
     * <p>So the probe removes FORCE and watches the foreign row appear.
     */
    @Test
    void without_force_the_owner_walks_straight_past_the_policy() throws SQLException {
        String ownSlug = "force-probe-own-" + tenantA;
        String foreignSlug = "force-probe-foreign-" + tenantB;

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            Db.insertScope(c, tenantA, ownSlug);
            Db.bindTenant(c, tenantB);
            Db.insertScope(c, tenantB, foreignSlug);
            c.commit();

            // The assertion is about the FOREIGN row specifically, not about a
            // total. Without FORCE the owner sees every row in the table,
            // including rows other tests planted, so a total would depend on
            // execution order and would report the wrong thing when it failed.
            Db.bindTenant(c, tenantA);
            assertThat(countBySlug(c, foreignSlug))
                .as("green state: with FORCE, the owner is bound by its own policy and the "
                    + "other tenant's row is not there")
                .isZero();

            try {
                Db.exec(c, "ALTER TABLE dispatch.scope NO FORCE ROW LEVEL SECURITY");
                c.commit();
                Db.bindTenant(c, tenantA);

                assertThat(countBySlug(c, foreignSlug))
                    .as("RED STATE, observed: with FORCE removed, the same session under the "
                        + "same tenant reads the other tenant's row. The policy still exists "
                        + "and still says the right thing; it simply does not apply to the "
                        + "owner. This is what a schema carrying ENABLE without FORCE looks "
                        + "like from the inside — no error, no warning, and every row of "
                        + "every tenant returned")
                    .isEqualTo(1);
            } finally {
                Db.exec(c, "ALTER TABLE dispatch.scope FORCE ROW LEVEL SECURITY");
                c.commit();
            }

            Db.bindTenant(c, tenantA);
            assertThat(countBySlug(c, foreignSlug))
                .as("and restored: the isolation is back, so the red state above was the "
                    + "removal and not some other change")
                .isZero();
        }
    }

    /**
     * Counts rows with a given slug as the CURRENT session sees them —
     * which, under a policy, is the only sense of "sees" that matters.
     */
    private static long countBySlug(Connection c, String slug) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT count(*) FROM dispatch.scope WHERE slug = ?")) {
            st.setString(1, slug);
            try (var rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * The write half. A policy with {@code USING} but no {@code WITH CHECK}
     * would let a session insert a row under a foreign tenant and then lose
     * sight of it — data planted across the boundary, invisible to the planter
     * and to the tenant that now owns it. The refusal is the database's, and
     * it names row-level security.
     */
    @Test
    void a_write_under_a_foreign_tenant_is_refused_by_the_policy() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            try {
                Db.insertScope(c, tenantB, "planted-across-the-boundary");
                throw new AssertionError(
                    "a session bound to tenant A inserted a row owned by tenant B — WITH CHECK "
                        + "is missing from the policy, and every write path can now cross the "
                        + "boundary the reads defend");
            } catch (SQLException expected) {
                assertThat(expected.getMessage())
                    .as("the refusal must come from the policy rather than from a constraint "
                        + "that happens to fire first")
                    .contains("row-level security");
            } finally {
                c.rollback();
            }
        }
    }
}
