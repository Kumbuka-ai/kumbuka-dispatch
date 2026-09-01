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
            Db.insertExchange(c, tenantA, "probe-a");
            Db.bindTenant(c, tenantB);
            Db.insertExchange(c, tenantB, "probe-b");
            c.commit();

            Db.bindTenant(c, tenantA);
            assertThat(Db.countExchanges(c))
                .as("a session bound to tenant A must see A's row and only A's")
                .isEqualTo(1);

            Db.bindTenant(c, tenantB);
            assertThat(Db.countExchanges(c))
                .as("and symmetrically for B — otherwise the filter is not a filter but "
                    + "a coincidence about which rows happen to exist")
                .isEqualTo(1);
        }
    }

    /**
     * The red half of probe 1, and the reason FORCE is in V3.
     *
     * <p>{@code ENABLE ROW LEVEL SECURITY} switches a policy on for every role
     * EXCEPT the table's owner, and {@code FORCE} is what binds the owner too.
     *
     * <p><strong>Which role is the owner changed when the service role stopped
     * owning its schema, and this probe moved with it.</strong> It used to read
     * under the service role, because the service owned its own tables — which
     * is exactly the arrangement that gave it the full privilege set
     * implicitly and had to go. The owner is
     * the migrator now, so the measurement is made under the migrator: it is
     * the role FORCE is load-bearing FOR, and it is not a hypothetical one,
     * because V5 carries DML and runs under it.
     *
     * <p>The service role gained something in the move and it is asserted
     * separately below: not owning the table, it is bound by ENABLE alone, so
     * for the one role that serves requests the exemption is not reachable at
     * all. FORCE is what covers the other one.
     */
    @Test
    void without_force_the_owner_walks_straight_past_the_policy() throws SQLException {
        String ownSlug = "force-probe-own-" + tenantA;
        String foreignSlug = "force-probe-foreign-" + tenantB;

        try (Connection planter = Db.asService()) {
            Db.bindTenant(planter, tenantA);
            Db.insertExchange(planter, tenantA, ownSlug);
            Db.bindTenant(planter, tenantB);
            Db.insertExchange(planter, tenantB, foreignSlug);
            planter.commit();
        }

        try (Connection c = Db.asMigrator()) {
            // The assertion is about the FOREIGN row specifically, not about a
            // total. Without FORCE the owner sees every row in the table,
            // including rows other tests planted, so a total would depend on
            // execution order and would report the wrong thing when it failed.
            Db.bindTenant(c, tenantA);
            assertThat(countByTitle(c, foreignSlug))
                .as("green state: with FORCE, the owner is bound by its own policy and the "
                    + "other tenant's row is not there")
                .isZero();
            c.commit();

            try {
                Db.switchPolicyAsOwner(c, "ALTER TABLE dispatch.exchange NO FORCE ROW LEVEL SECURITY");
                Db.bindTenant(c, tenantA);

                assertThat(countByTitle(c, foreignSlug))
                    .as("RED STATE, observed: with FORCE removed, the owner under the same "
                        + "tenant binding reads the other tenant's row. The policy still "
                        + "exists and still says the right thing; it simply does not apply "
                        + "to the owner. This is what a schema carrying ENABLE without "
                        + "FORCE looks like from the inside — no error, no warning, and "
                        + "every row of every tenant returned")
                    .isEqualTo(1);
                c.commit();
            } finally {
                Db.switchPolicyAsOwner(c, "ALTER TABLE dispatch.exchange FORCE ROW LEVEL SECURITY");
            }

            Db.bindTenant(c, tenantA);
            assertThat(countByTitle(c, foreignSlug))
                .as("and restored: the isolation is back, so the red state above was the "
                    + "removal and not some other change")
                .isZero();
        }
    }

    /**
     * What the ownership change bought, stated as its own assertion: the role
     * that serves requests cannot reconfigure the policy that binds it.
     *
     * <p>An owner may {@code DROP POLICY}, may {@code DISABLE ROW LEVEL
     * SECURITY} and may take {@code FORCE} off. {@code FORCE} subjects an
     * owner to its policies; it does not stop the owner removing them. So for
     * as long as the service role owned its tables, the isolation rested on
     * that role choosing not to switch itself out of it — which is an
     * assurance of a different and much weaker kind than the one this schema
     * is supposed to carry.
     *
     * <p>Three statements, three refusals, each on the SQLSTATE rather than on
     * message text. The three are separate because they are three different
     * routes to the same end and a role could be refused one and allowed
     * another.
     */
    @Test
    void the_service_role_cannot_switch_off_the_policy_that_binds_it() throws SQLException {
        assertThat(refusalFor("ALTER TABLE dispatch.exchange NO FORCE ROW LEVEL SECURITY"))
            .as("taking FORCE off would exempt the table's owner — and would be the first "
                + "step back to a service role that owns what it reads")
            .isEqualTo("42501");

        assertThat(refusalFor("ALTER TABLE dispatch.exchange DISABLE ROW LEVEL SECURITY"))
            .as("switching the policy off entirely is the shorter route to the same place")
            .isEqualTo("42501");

        assertThat(refusalFor("DROP POLICY exchange_tenant_isolation ON dispatch.exchange"))
            .as("and removing the policy is the route FORCE cannot cover at all, because "
                + "FORCE binds an owner to its policies without stopping it deleting them")
            .isEqualTo("42501");

        // The policy is still there and still doing its work, so the three
        // refusals above were refusals and not statements that ran and quietly
        // achieved nothing. A row is planted under one tenant and looked for
        // under the other, in this method, so the check does not depend on
        // what any other test left behind.
        String slug = "reconfigure-probe-" + tenantA;
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            Db.insertExchange(c, tenantA, slug);
            c.commit();

            Db.bindTenant(c, tenantB);
            assertThat(countByTitle(c, slug))
                .as("and the isolation still holds afterwards — a DISABLE that had gone "
                    + "through would show up here as the other tenant's row")
                .isZero();

            Db.bindTenant(c, tenantA);
            assertThat(countByTitle(c, slug))
                .as("while the row is genuinely there, which is what makes the emptiness "
                    + "above a filter rather than a failed insert")
                .isEqualTo(1);
        }
    }

    /**
     * Runs a statement as the service role and reports the SQLSTATE it was
     * refused with. Any success is reported as such rather than folded into a
     * pass, because a statement that ran is the failure this is looking for.
     */
    private static String refusalFor(String ddl) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.exec(c, ddl);
            c.rollback();
            return "not refused: the statement ran";
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    /**
     * Counts rows with a given title as the CURRENT session sees them —
     * which, under a policy, is the only sense of "sees" that matters.
     */
    private static long countByTitle(Connection c, String title) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT count(*) FROM dispatch.exchange WHERE title = ?")) {
            st.setString(1, title);
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
                Db.insertExchange(c, tenantB, "planted-across-the-boundary");
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
